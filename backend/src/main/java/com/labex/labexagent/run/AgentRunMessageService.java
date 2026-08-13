package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 运行级 Message 持久化；它是 Part 的稳定父容器，不替代历史会话消息表。 */
@Service
public class AgentRunMessageService {
    private static final Gson GSON = new Gson();
    private final AgentRunMessageMapper messageMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunExecutionLeaseService leaseService;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunMessageService(AgentRunMessageMapper messageMapper, AgentTaskMapper taskMapper,
                                  AgentRunExecutionLeaseService leaseService) {
        this.messageMapper = messageMapper;
        this.taskMapper = taskMapper;
        this.leaseService = leaseService;
    }

    public AgentRunMessageService(AgentRunMessageMapper messageMapper, AgentTaskMapper taskMapper) {
        this(messageMapper, taskMapper, new AgentRunExecutionLeaseService(taskMapper, "legacy-instance", 30_000L));
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunMessage upsertAssistantTurn(Long taskId, long iteration, String status) {
        return upsert(taskId, "assistant:turn:" + iteration, iteration, "assistant", status,
                "", Map.of("iteration", iteration));
    }

    /**
     * Executor-fenced assistant turn 写入：先验证 {@link ExecutionFence}（owner + 精确 epoch + 未过期 lease），
     * stale fence 抛出 typed failure，Message 行不被写入。
     * 该预检在写入事务内、任何 INSERT/UPDATE 之前执行；lifecycle/plan 的 fenced 写入另将
     * owner/epoch/active-lease 嵌入 UPDATE 谓词。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunMessage upsertAssistantTurn(ExecutionFence fence, Long taskId, long iteration, String status) {
        requireFence(fence);
        return upsertAssistantTurn(taskId, iteration, status);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunMessage recordEventMessage(Long taskId, String eventType, Object payload, long sequence) {
        if (taskId == null || eventType == null || eventType.isBlank()) return null;
        Map<String, Object> data = payload instanceof Map<?, ?> map
                ? copyMap(map) : new LinkedHashMap<>(Map.of("value", payload == null ? "" : payload));
        sanitizeReasoningPayload(eventType, data);
        String role = eventType.startsWith("RUN_") || eventType.startsWith("CONTEXT_")
                || eventType.startsWith("COMPACTION_") ? "system" : "assistant";
        String key = switch (eventType) {
            case "FINAL" -> "assistant:final";
            case "THINK" -> "assistant:turn:" + stringValue(data, "iteration", String.valueOf(sequence));
            default -> "event:" + eventType.toLowerCase() + ":" + sequence;
        };
        String status = eventType.equals("ERROR") || eventType.endsWith("FAILED") ? "error" : "completed";
        String content = stringValue(data, "content", "message", "summary");
        return upsert(taskId, key, sequence, role, status, content, data);
    }

    /**
     * Executor-fenced 事件消息写入：先验证 {@link ExecutionFence}，stale fence 抛出 typed failure。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunMessage recordEventMessage(ExecutionFence fence, Long taskId, String eventType,
                                              Object payload, long sequence) {
        requireFence(fence);
        return recordEventMessage(taskId, eventType, payload, sequence);
    }

    /** 为重启恢复封口未完成消息，避免刷新后永久显示 streaming。 */
    @Transactional(rollbackFor = Exception.class)
    public int markOpenMessages(Long taskId, String status, String reason) {
        if (taskId == null || taskId <= 0) return 0;
        List<AgentRunMessage> open = messageMapper.selectList(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId)
                .in(AgentRunMessage::getStatus, List.of("pending", "running", "streaming")));
        int updated = 0;
        for (AgentRunMessage message : open) {
            message.setStatus(status == null || status.isBlank() ? "interrupted" : status);
            if ((message.getContent() == null || message.getContent().isBlank()) && reason != null) {
                message.setContent(limit(reason));
            }
            message.setUpdateTime(LocalDateTime.now());
            updated += messageMapper.updateById(message) == 1 ? 1 : 0;
        }
        return updated;
    }

    public List<AgentRunMessage> history(Long taskId) {
        if (taskId == null || taskId <= 0) return List.of();
        return messageMapper.selectList(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId)
                .orderByAsc(AgentRunMessage::getRunMessageId));
    }

    public List<Map<String, Object>> publicHistory(Long taskId) {
        return history(taskId).stream().map(this::publicPayload).toList();
    }

    /** 按任务批量读取公开消息投影，避免历史页产生 task N+1 查询。 */
    public Map<Long, List<Map<String, Object>>> publicHistoryByTaskIds(Collection<Long> taskIds) {
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        List<AgentRunMessage> stored = messageMapper.selectList(new LambdaQueryWrapper<AgentRunMessage>()
                .in(AgentRunMessage::getTaskId, ids)
                .orderByAsc(AgentRunMessage::getTaskId)
                .orderByAsc(AgentRunMessage::getRunMessageId));
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (AgentRunMessage message : stored == null ? List.<AgentRunMessage>of() : stored) {
            if (message == null || message.getTaskId() == null) continue;
            grouped.computeIfAbsent(message.getTaskId(), ignored -> new java.util.ArrayList<>())
                    .add(publicPayload(message));
        }
        grouped.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(grouped);
    }

    private AgentRunMessage upsert(Long taskId, String messageKey, long sequence, String role,
                                   String status, String content, Object metadata) {
        LocalDateTime now = LocalDateTime.now();
        AgentRunMessage message = messageMapper.selectOne(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId)
                .eq(AgentRunMessage::getMessageKey, messageKey)
                .last("LIMIT 1"));
        AgentTask task = taskMapper.selectById(taskId);
        if (message == null) {
            message = new AgentRunMessage();
            message.setTaskId(taskId);
            message.setMessageKey(messageKey);
            message.setCreateTime(now);
            if (task != null) {
                message.setConversationId(task.getConversationId());
                message.setStudentId(task.getStudentId());
                message.setProjectId(task.getProjectId());
            }
        }
        message.setSequenceNumber(sequence);
        message.setRole(role);
        message.setStatus(status);
        // opencode 语义：内容完整落库，不静默截断；模型输出由 Provider max tokens 约束，
        // 请求预算由上下文准入/压缩在读取期管理，FINAL 长回答才能完整进入跨轮会话记忆。
        message.setContent(content == null ? "" : content);
        message.setMetadata(GSON.toJson(metadata == null ? Map.of() : metadata));
        message.setUpdateTime(now);
        if (message.getRunMessageId() == null) {
            messageMapper.insert(message);
        } else {
            messageMapper.updateById(message);
        }
        return message;
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private void sanitizeReasoningPayload(String eventType, Map<String, Object> data) {
        if (!"FINAL".equals(eventType) && !eventType.startsWith("THINK")) return;
        for (String key : List.of("content", "message", "summary", "delta")) {
            Object value = data.get(key);
            if (value == null) continue;
            boolean reasoningBody = !"FINAL".equals(eventType)
                    && ("content".equals(key) || "delta".equals(key));
            String safe = reasoningBody
                    ? InternalReasoningBoundary.stripTags(String.valueOf(value))
                    : InternalReasoningBoundary.stripVisible(String.valueOf(value));
            data.put(key, safe);
        }
    }

    private String stringValue(Map<String, Object> data, String first, String fallback) {
        Object value = data.get(first);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String stringValue(Map<String, Object> data, String first, String second, String fallback) {
        Object value = data.get(first);
        if (value == null || String.valueOf(value).isBlank()) value = data.get(second);
        if (value == null || String.valueOf(value).isBlank()) value = data.get(fallback);
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> publicPayload(AgentRunMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.getRunMessageId());
        payload.put("messageKey", message.getMessageKey());
        payload.put("sequence", message.getSequenceNumber());
        payload.put("role", message.getRole());
        payload.put("status", message.getStatus());
        payload.put("content", publicContent(message));
        payload.put("metadata", message.getMetadata());
        payload.put("createdAt", message.getCreateTime());
        payload.put("updatedAt", message.getUpdateTime());
        return payload;
    }

    private String publicContent(AgentRunMessage message) {
        String content = message.getContent();
        String key = message.getMessageKey() == null ? "" : message.getMessageKey();
        if ("assistant:final".equals(key)) return InternalReasoningBoundary.stripVisible(content);
        if (key.startsWith("assistant:turn:")) return InternalReasoningBoundary.stripTags(content);
        return content;
    }

    /**
     * 只用于中断补白（markOpenMessages 为空消息填入原因）的防御性上限；
     * 正常消息内容路径不做任何截断。
     */
    private String limit(String value) {
        if (value == null) return "";
        return value.length() <= 64_000 ? value : value.substring(0, 64_000) + "\n...truncated...";
    }

    private void requireFence(ExecutionFence fence) {
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for executor-originated writes");
        }
        leaseService.requireActiveFence(fence, LocalDateTime.now());
    }
}