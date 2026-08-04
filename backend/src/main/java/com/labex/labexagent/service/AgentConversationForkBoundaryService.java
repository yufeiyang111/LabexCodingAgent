package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 会话分叉边界的唯一解析入口。
 *
 * <p>新分叉只写 durable task ID。旧 messageId 仅用于一次性兼容映射；旧行完成回填后，
 * Provider 投影不再依赖 {@code t_agent_message}。</p>
 */
@Service
public class AgentConversationForkBoundaryService {
    private static final Gson GSON = new Gson();
    private static final int TASK_LIMIT = 10_001;
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed", "cancelled");

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentTaskMapper taskMapper;

    public AgentConversationForkBoundaryService(AgentConversationMapper conversationMapper,
                                                AgentMessageMapper messageMapper,
                                                AgentTaskMapper taskMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.taskMapper = taskMapper;
    }

    public Long resolveNewFork(Integer studentId, Integer projectId, String sourceConversationId,
                               Long legacyMessageId, Long requestedTaskId) {
        List<AgentTask> stable = stablePrefix(studentId, projectId, sourceConversationId);
        if (requestedTaskId != null && requestedTaskId > 0) {
            boolean accepted = stable.stream().anyMatch(task -> requestedTaskId.equals(task.getTaskId()));
            if (!accepted) {
                throw new IllegalArgumentException("Requested fork task is outside the stable terminal prefix");
            }
            return requestedTaskId;
        }
        if (legacyMessageId != null && legacyMessageId > 0) {
            AgentMessage legacy = requireOwnedMessage(studentId, projectId, sourceConversationId, legacyMessageId);
            Long embeddedTaskId = embeddedTaskId(legacy);
            if (embeddedTaskId != null) {
                boolean accepted = stable.stream().anyMatch(task -> embeddedTaskId.equals(task.getTaskId()));
                if (!accepted) {
                    throw new IllegalArgumentException("Legacy fork message points outside the stable terminal prefix");
                }
                return embeddedTaskId;
            }
            return latestNotAfter(stable, legacy.getCreateTime());
        }
        return stable.isEmpty() ? null : stable.get(stable.size() - 1).getTaskId();
    }

    /** 对迁移前的 fork 行做保守、幂等的 lazy backfill。 */
    public LocalDateTime copyCutoff(Integer studentId, Integer projectId,
                                    String sourceConversationId, Long taskId) {
        if (taskId == null || taskId <= 0) {
            return null;
        }
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null
                || !equals(studentId, task.getStudentId())
                || !equals(projectId, task.getProjectId())
                || !equals(sourceConversationId, task.getConversationId())
                || !isTerminal(task.getStatus())) {
            throw new IllegalArgumentException("Fork task boundary no longer belongs to the source conversation");
        }
        if (task.getFinishedAt() != null) {
            return task.getFinishedAt();
        }
        if (task.getUpdateTime() != null) {
            return task.getUpdateTime();
        }
        return task.getCreateTime();
    }

    public Long resolveExistingFork(AgentConversation child) {
        if (child == null || child.getParentConversationId() == null
                || child.getParentConversationId().isBlank()) {
            return null;
        }
        if (child.getForkedFromTaskId() != null && child.getForkedFromTaskId() > 0) {
            return child.getForkedFromTaskId();
        }
        List<AgentTask> stable = stablePrefix(
                child.getStudentId(), child.getProjectId(), child.getParentConversationId());
        Long boundary = null;
        if (child.getForkedFromMessageId() != null && child.getForkedFromMessageId() > 0) {
            AgentMessage legacy = requireOwnedMessage(child.getStudentId(), child.getProjectId(),
                    child.getParentConversationId(), child.getForkedFromMessageId());
            Long embeddedTaskId = embeddedTaskId(legacy);
            if (embeddedTaskId != null
                    && stable.stream().anyMatch(task -> embeddedTaskId.equals(task.getTaskId()))) {
                boundary = embeddedTaskId;
            } else {
                boundary = latestNotAfter(stable, legacy.getCreateTime());
            }
        } else {
            boundary = latestNotAfter(stable, child.getCreateTime());
        }
        if (boundary == null || boundary <= 0) {
            return null;
        }
        int updated = conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, child.getConversationId())
                .eq(AgentConversation::getStudentId, child.getStudentId())
                .eq(AgentConversation::getProjectId, child.getProjectId())
                .isNull(AgentConversation::getForkedFromTaskId)
                .set(AgentConversation::getForkedFromTaskId, boundary));
        if (updated == 1) {
            child.setForkedFromTaskId(boundary);
            return boundary;
        }
        AgentConversation reloaded = ownedConversation(
                child.getStudentId(), child.getProjectId(), child.getConversationId());
        if (reloaded != null && reloaded.getForkedFromTaskId() != null
                && reloaded.getForkedFromTaskId() > 0) {
            child.setForkedFromTaskId(reloaded.getForkedFromTaskId());
            return reloaded.getForkedFromTaskId();
        }
        throw new IllegalStateException("Unable to persist legacy fork task boundary");
    }

    private List<AgentTask> stablePrefix(Integer studentId, Integer projectId, String conversationId) {
        if (studentId == null || projectId == null || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<AgentTask> selected = taskMapper.selectList(new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getStudentId, studentId)
                .eq(AgentTask::getProjectId, projectId)
                .eq(AgentTask::getConversationId, conversationId)
                .orderByAsc(AgentTask::getTaskId)
                .last("LIMIT " + TASK_LIMIT));
        List<AgentTask> ordered = selected == null ? List.of() : selected.stream()
                .filter(task -> task != null && task.getTaskId() != null)
                .filter(task -> equals(studentId, task.getStudentId()))
                .filter(task -> equals(projectId, task.getProjectId()))
                .filter(task -> equals(conversationId, task.getConversationId()))
                .sorted(Comparator.comparing(AgentTask::getTaskId))
                .toList();
        if (ordered.size() >= TASK_LIMIT) {
            throw new IllegalStateException("Conversation task history exceeds the bounded fork scan; compact it first");
        }
        int stableCount = 0;
        for (AgentTask task : ordered) {
            if (!isTerminal(task.getStatus())) {
                break;
            }
            stableCount++;
        }
        return List.copyOf(ordered.subList(0, stableCount));
    }

    private AgentMessage requireOwnedMessage(Integer studentId, Integer projectId,
                                             String conversationId, Long messageId) {
        AgentMessage message = messageMapper.selectById(messageId);
        if (message == null
                || !equals(studentId, message.getStudentId())
                || !equals(projectId, message.getProjectId())
                || !equals(conversationId, message.getConversationId())) {
            throw new IllegalArgumentException("Legacy fork message does not belong to the source conversation");
        }
        return message;
    }

    private Long embeddedTaskId(AgentMessage message) {
        if (message == null || message.getEventData() == null || message.getEventData().isBlank()) {
            return null;
        }
        try {
            Map<?, ?> parsed = GSON.fromJson(message.getEventData(), Map.class);
            Object raw = parsed == null ? null : parsed.get("taskId");
            if (raw instanceof Number number) {
                return number.longValue();
            }
            if (raw instanceof String text && !text.isBlank()) {
                return Long.valueOf(text);
            }
        } catch (RuntimeException ignored) {
            // 旧事件 JSON 可能损坏；仅退化到时间边界，不把它作为新的事实源。
        }
        return null;
    }

    private Long latestNotAfter(List<AgentTask> stable, LocalDateTime cutoff) {
        if (cutoff == null) {
            return null;
        }
        Long boundary = null;
        for (AgentTask task : stable) {
            LocalDateTime taskTime = task.getFinishedAt();
            if (taskTime == null) {
                taskTime = task.getUpdateTime();
            }
            if (taskTime == null) {
                taskTime = task.getCreateTime();
            }
            if (taskTime != null && !taskTime.isAfter(cutoff)) {
                boundary = task.getTaskId();
            }
        }
        return boundary;
    }

    private AgentConversation ownedConversation(Integer studentId, Integer projectId, String conversationId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getStudentId, studentId)
                .eq(AgentConversation::getProjectId, projectId)
                .eq(AgentConversation::getConversationId, conversationId)
                .eq(AgentConversation::getStatus, 1));
    }

    private boolean isTerminal(String status) {
        return status != null && TERMINAL_STATUSES.contains(status.trim().toLowerCase(Locale.ROOT));
    }

    private boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}