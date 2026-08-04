package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentTask;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 从任务请求与运行级最终消息投影会话 transcript。
 *
 * <p>该投影不读取 {@code t_agent_message}，并且只推进到连续的终态任务前缀，
 * 避免把仍可能变化的运行中任务纳入压缩边界。</p>
 */
@Service
public class AgentConversationTranscriptProjectionService {
    private static final Gson GSON = new Gson();
    private static final int TASK_BATCH_LIMIT = 500;
    private static final int CONTENT_LIMIT = 12_000;
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed", "cancelled");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{10,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~-]{10,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|authorization|token|password|secret)\\s*[:=]\\s*([^\\s,;\\]}]+)");

    private final AgentTaskMapper taskMapper;
    private final AgentRunMessageMapper messageMapper;

    public AgentConversationTranscriptProjectionService(AgentTaskMapper taskMapper,
                                                        AgentRunMessageMapper messageMapper) {
        this.taskMapper = taskMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 读取 {@code (afterTaskIdExclusive, beforeTaskIdExclusive)} 内最早的一批稳定任务。
     * 返回的 sourceMaxTaskId 是可安全作为下一次增量读取起点的连续边界。
     */
    public Snapshot snapshot(Integer studentId, Integer projectId, String conversationId,
                             long afterTaskIdExclusive, Long beforeTaskIdExclusive) {
        if (studentId == null || projectId == null || conversationId == null || conversationId.isBlank()) {
            return new Snapshot(List.of(), Math.max(0L, afterTaskIdExclusive), 0, false);
        }
        long after = Math.max(0L, afterTaskIdExclusive);
        LambdaQueryWrapper<AgentTask> query = new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getStudentId, studentId)
                .eq(AgentTask::getProjectId, projectId)
                .eq(AgentTask::getConversationId, conversationId)
                .gt(after > 0, AgentTask::getTaskId, after)
                .lt(beforeTaskIdExclusive != null && beforeTaskIdExclusive > 0,
                        AgentTask::getTaskId, beforeTaskIdExclusive)
                .orderByAsc(AgentTask::getTaskId)
                .last("LIMIT " + (TASK_BATCH_LIMIT + 1));
        List<AgentTask> selected = taskMapper.selectList(query);
        if (selected == null || selected.isEmpty()) {
            return new Snapshot(List.of(), after, 0, false);
        }

        List<AgentTask> ordered = selected.stream()
                .filter(task -> task != null && task.getTaskId() != null)
                .filter(task -> task.getTaskId() > after)
                .filter(task -> beforeTaskIdExclusive == null || beforeTaskIdExclusive <= 0
                        || task.getTaskId() < beforeTaskIdExclusive)
                .sorted(Comparator.comparing(AgentTask::getTaskId))
                .toList();
        if (ordered.isEmpty()) {
            return new Snapshot(List.of(), after, 0, false);
        }
        boolean queryHasMore = ordered.size() > TASK_BATCH_LIMIT;
        List<AgentTask> tasks = queryHasMore ? ordered.subList(0, TASK_BATCH_LIMIT) : ordered;

        Map<Long, AgentRunMessage> finalsByTask = loadFinalMessages(tasks);
        List<Map<String, Object>> messages = new ArrayList<>();
        long sourceMaxTaskId = after;
        int userTurns = 0;
        int stableTasks = 0;
        boolean unstableBarrier = false;
        for (AgentTask task : tasks) {
            if (!isTerminal(task.getStatus())) {
                unstableBarrier = true;
                break;
            }
            stableTasks++;
            sourceMaxTaskId = task.getTaskId();
            if ("compact".equalsIgnoreCase(task.getMode())) {
                continue;
            }
            String request = durableRequest(task);
            if (request.isBlank()) {
                continue;
            }
            messages.add(message("user", request));
            userTurns++;
            AgentRunMessage finalMessage = finalsByTask.get(task.getTaskId());
            if (finalMessage != null) {
                String answer = sanitizeVisible(finalMessage.getContent());
                if (!answer.isBlank()) {
                    messages.add(message("assistant", answer));
                }
            }
        }
        boolean hasMore = !unstableBarrier && queryHasMore && stableTasks == TASK_BATCH_LIMIT;
        return new Snapshot(messages, sourceMaxTaskId, userTurns, hasMore);
    }

    private Map<Long, AgentRunMessage> loadFinalMessages(List<AgentTask> tasks) {
        List<Long> taskIds = tasks.stream().map(AgentTask::getTaskId).toList();
        List<AgentRunMessage> stored = messageMapper.selectList(new LambdaQueryWrapper<AgentRunMessage>()
                .in(AgentRunMessage::getTaskId, taskIds)
                .eq(AgentRunMessage::getMessageKey, "assistant:final")
                .orderByAsc(AgentRunMessage::getRunMessageId));
        if (stored == null || stored.isEmpty()) {
            return Map.of();
        }
        Map<Long, AgentRunMessage> finals = new HashMap<>();
        for (AgentRunMessage message : stored) {
            if (message == null || message.getTaskId() == null) {
                continue;
            }
            AgentRunMessage existing = finals.get(message.getTaskId());
            if (existing == null || id(message) >= id(existing)) {
                finals.put(message.getTaskId(), message);
            }
        }
        return finals;
    }

    private long id(AgentRunMessage message) {
        return message.getRunMessageId() == null ? 0L : message.getRunMessageId();
    }

    private boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.trim().toLowerCase(Locale.ROOT));
    }

    private String durableRequest(AgentTask task) {
        String payload = task.getRequestPayload();
        if (payload != null && !payload.isBlank()) {
            try {
                Map<?, ?> parsed = GSON.fromJson(payload, Map.class);
                Object value = parsed == null ? null : parsed.get("message");
                if (value != null && !String.valueOf(value).isBlank()) {
                    return sanitizeVisible(String.valueOf(value));
                }
            } catch (RuntimeException ignored) {
                // 老任务可能只有 title；兼容读取不能重新依赖旧 AgentMessage 表。
            }
        }
        return sanitizeVisible(task.getTitle());
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("content", content);
        return result;
    }

    private String sanitizeVisible(String value) {
        String visible = InternalReasoningBoundary.stripVisible(value == null ? "" : value);
        String redacted = API_KEY.matcher(visible).replaceAll("[REDACTED]");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer [REDACTED]");
        redacted = NAMED_SECRET.matcher(redacted).replaceAll("$1=[REDACTED]");
        if (redacted.length() <= CONTENT_LIMIT) {
            return redacted;
        }
        return redacted.substring(0, CONTENT_LIMIT) + "\n...truncated...";
    }

    public record Snapshot(List<Map<String, Object>> messages, long sourceMaxTaskId,
                           int userTurns, boolean hasMore) {
        public Snapshot(List<Map<String, Object>> messages, long sourceMaxTaskId, int userTurns) {
            this(messages, sourceMaxTaskId, userTurns, false);
        }

        public Snapshot {
            messages = List.copyOf(messages == null ? List.of() : messages);
            sourceMaxTaskId = Math.max(0L, sourceMaxTaskId);
            userTurns = Math.max(0, userTurns);
        }
    }
}
