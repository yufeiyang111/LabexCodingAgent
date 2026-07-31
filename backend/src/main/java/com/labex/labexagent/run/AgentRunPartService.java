package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一管理 OpenCode 风格的运行 Part。
 *
 * <p>Part 是可更新的运行事实，而不是瞬时 SSE。工具调用状态变化只更新同一个 part_key，
 * 这样重试、重连和刷新都能得到一个稳定的运行单元。</p>
 */
@Service
public class AgentRunPartService {
    private static final Gson GSON = new Gson();
    private final AgentRunPartMapper partMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunMessageService messageService;

    @Autowired
    public AgentRunPartService(AgentRunPartMapper partMapper, AgentTaskMapper taskMapper,
                               AgentRunMessageService messageService) {
        this.partMapper = partMapper;
        this.taskMapper = taskMapper;
        this.messageService = messageService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunPart upsertToolCall(Long taskId, String toolCallId, String status, String toolName,
                                       Object arguments, int iteration, String detail) {
        if (taskId == null || taskId <= 0 || toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        syncProviderToolCallState(taskId, toolCallId, status, detail);
        String messageStatus = switch (status == null ? "" : status) {
            case "waiting_approval", "waiting_user" -> "waiting";
            case "completed", "skipped" -> "completed";
            case "error", "environment_blocked", "interrupted" -> "error";
            default -> "streaming";
        };
        AgentRunMessage message = messageService.upsertAssistantTurn(taskId, iteration, messageStatus);
        return upsertPart(taskId, message.getRunMessageId(),
                "tool:" + toolCallId.trim(), "tool", status, toolCallId,
                toolName, arguments, detail, iteration);
    }

    /** 同步 Provider tool_call Part 的生命周期，保证等待交互在 JVM 重启后仍可恢复。 */
    private void syncProviderToolCallState(Long taskId, String toolCallId, String status, String detail) {
        List<AgentRunPart> providerCalls = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .eq(AgentRunPart::getPartType, "tool_call"));
        if (providerCalls == null || providerCalls.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (AgentRunPart providerCall : providerCalls) {
            providerCall.setStatus(status == null || status.isBlank() ? "error" : status);
            providerCall.setOutputText(limit(detail));
            providerCall.setUpdateTime(now);
            partMapper.updateById(providerCall);
        }
    }

    /** 更新已存在的工具 Part，用于命令审批等延后终态的持久化。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunPart resolveExistingToolCall(Long taskId, String toolCallId, String status, String detail) {
        if (taskId == null || taskId <= 0 || toolCallId == null || toolCallId.isBlank()) return null;
        AgentRunPart part = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .last("LIMIT 1"));
        if (part == null) return null;
        part.setStatus(status == null || status.isBlank() ? "error" : status);
        part.setOutputText(limit(detail));
        part.setUpdateTime(LocalDateTime.now());
        partMapper.updateById(part);
        if (part.getSequenceNumber() != null) {
            String messageStatus = "completed".equals(part.getStatus()) || "skipped".equals(part.getStatus())
                    ? "completed" : "error";
            messageService.upsertAssistantTurn(taskId, part.getSequenceNumber(), messageStatus);
        }
        return part;
    }

    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public AgentRunPart recordEventPart(Long taskId, String eventType, Object payload, long sequence) {
        if (taskId == null || eventType == null || eventType.isBlank() || !supportsEventPart(eventType)) return null;
        Map<String, Object> data = payload instanceof Map<?, ?> map
                ? copyMap(map) : Map.of("value", payload == null ? "" : payload);
        AgentRunMessage message = messageService.recordEventMessage(taskId, eventType, data, sequence);
        Long messageId = message.getRunMessageId();
        return switch (eventType) {
            case "THINK" -> upsertPart(taskId, messageId, "reasoning:" + sequence, "reasoning", "completed",
                    null, null, data, text(data, "message", "content"), sequence);
            case "FINAL" -> upsertPart(taskId, messageId, "final", "text", "completed",
                    null, null, data, text(data, "content", "message"), sequence);
            case "ERROR" -> upsertPart(taskId, messageId, "error:" + sequence, "error", "error",
                    null, null, data, text(data, "message", "content"), sequence);
            case "COMPLETION_EVIDENCE" -> {
                boolean satisfied = Boolean.TRUE.equals(data.get("satisfied"));
                yield upsertPart(taskId, messageId, "completion:evidence", "completion_evidence",
                        satisfied ? "completed" : "error", null, null, data, GSON.toJson(data), sequence);
            }
            case "CONTEXT_STATUS", "CONTEXT_STATS", "COMPACTION_STARTED",
                 "COMPACTION_COMPLETED", "COMPACTION_FAILED", "CONTEXT_PRUNED" ->
                    upsertPart(taskId, messageId, "context:" + sequence, "context", "completed",
                            null, null, data, GSON.toJson(data), sequence);
            case "RUN_STATE_WAITING_APPROVAL", "RUN_STATE_WAITING_USER",
                 "RUN_INTERACTION_RESUME_QUEUED", "RUN_INTERACTION_TIMED_OUT" ->
                    upsertPart(taskId, messageId, "interaction:" + sequence, "interaction",
                            eventType.endsWith("TIMED_OUT") ? "error" : "waiting",
                            null, null, data, GSON.toJson(data), sequence);
            case "RUN_ENVIRONMENT_BLOCKED", "RUN_STATE_WAITING_ENVIRONMENT" ->
                    upsertPart(taskId, messageId, "environment:blocker", "environment",
                            "environment_blocked", null, null, data, GSON.toJson(data), sequence);
            case "RUN_WORKSPACE_WAITING", "RUN_STATE_WAITING_WORKSPACE" ->
                    upsertPart(taskId, messageId, "workspace:waiting", "workspace",
                            "waiting", null, null, data, GSON.toJson(data), sequence);
            case "RUN_CANCELLATION_REQUESTED", "RUN_STATE_CANCELLING",
                 "RUN_CANCELLED", "RUN_STATE_CANCELLED", "INTERRUPTED",
                 "COMMAND_EXECUTION_INTERRUPTED" ->
                    upsertPart(taskId, messageId, "interruption:" + sequence, "interruption",
                            eventType.contains("CANCELLED") ? "cancelled" : "interrupted",
                            null, null, data, GSON.toJson(data), sequence);
            case "RUN_RECOVERY_TAKEOVER", "RUN_STATE_RECOVERING" ->
                    upsertPart(taskId, messageId, "recovery:" + sequence, "recovery",
                            "running", null, null, data, GSON.toJson(data), sequence);
            case "DONE", "RUN_STATE_COMPLETED", "RUN_STATE_FAILED" ->
                    upsertPart(taskId, messageId, "lifecycle:" + sequence, "lifecycle",
                            eventType.endsWith("FAILED") ? "error" : "completed",
                            null, null, data, GSON.toJson(data), sequence);
            default -> null;
        };
    }

    /** 将进程重启时遗留的 pending/running Part 转为明确的 interrupted 终态。 */
    @Transactional(rollbackFor = Exception.class)
    public int interruptOpenParts(Long taskId, String reason) {
        if (taskId == null || taskId <= 0) return 0;
        List<AgentRunPart> open = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .in(AgentRunPart::getStatus, List.of("pending", "running", "streaming")));
        int updated = 0;
        for (AgentRunPart part : open) {
            part.setStatus("interrupted");
            String detail = reason == null ? "Agent execution was interrupted" : reason;
            if (part.getOutputText() == null || part.getOutputText().isBlank()) {
                part.setOutputText(limit(detail));
            }
            part.setUpdateTime(LocalDateTime.now());
            updated += partMapper.updateById(part) == 1 ? 1 : 0;
        }
        return updated;
    }

    public List<AgentRunPart> history(Long taskId) {
        if (taskId == null || taskId <= 0) return List.of();
        return partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .orderByAsc(AgentRunPart::getPartId));
    }

    public List<Map<String, Object>> publicHistory(Long taskId) {
        return history(taskId).stream().map(this::publicPayload).toList();
    }

    private AgentRunPart upsertPart(Long taskId, Long messageId, String partKey, String partType, String status,
                                    String toolCallId, String toolName, Object input,
                                    String output, long sequence) {
        LocalDateTime now = LocalDateTime.now();
        AgentRunPart part = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getPartKey, partKey)
                .last("LIMIT 1"));
        AgentTask task = taskMapper.selectById(taskId);
        if (part == null) {
            part = new AgentRunPart();
            part.setTaskId(taskId);
            part.setPartKey(partKey);
            part.setPartType(partType);
            part.setCreateTime(now);
            if (task != null) {
                part.setConversationId(task.getConversationId());
                part.setStudentId(task.getStudentId());
                part.setProjectId(task.getProjectId());
            }
        }
        part.setMessageId(messageId);
        part.setPartType(partType);
        part.setStatus(status == null || status.isBlank() ? "unknown" : status);
        part.setToolCallId(toolCallId);
        part.setToolName(toolName == null ? "" : toolName);
        part.setInputJson(input == null ? "{}" : GSON.toJson(input));
        part.setOutputText(limit(output));
        part.setSequenceNumber(sequence);
        part.setMetadata(GSON.toJson(Map.of(
                "sequence", sequence,
                "partType", partType,
                "status", part.getStatus())));
        part.setUpdateTime(now);
        if (part.getPartId() == null) {
            partMapper.insert(part);
        } else {
            partMapper.updateById(part);
        }
        return part;
    }

    private boolean supportsEventPart(String eventType) {
        return switch (eventType) {
            case "THINK", "FINAL", "ERROR", "COMPLETION_EVIDENCE",
                 "CONTEXT_STATUS", "CONTEXT_STATS", "COMPACTION_STARTED",
                 "COMPACTION_COMPLETED", "COMPACTION_FAILED", "CONTEXT_PRUNED",
                 "RUN_STATE_WAITING_APPROVAL", "RUN_STATE_WAITING_USER",
                 "RUN_INTERACTION_RESUME_QUEUED", "RUN_INTERACTION_TIMED_OUT",
                 "RUN_ENVIRONMENT_BLOCKED", "RUN_STATE_WAITING_ENVIRONMENT",
                 "RUN_WORKSPACE_WAITING", "RUN_STATE_WAITING_WORKSPACE",
                 "RUN_CANCELLATION_REQUESTED", "RUN_STATE_CANCELLING",
                 "RUN_CANCELLED", "RUN_STATE_CANCELLED", "INTERRUPTED",
                 "COMMAND_EXECUTION_INTERRUPTED", "RUN_RECOVERY_TAKEOVER",
                 "RUN_STATE_RECOVERING", "DONE", "RUN_STATE_COMPLETED",
                 "RUN_STATE_FAILED" -> true;
            default -> false;
        };
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String text(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return GSON.toJson(data);
    }

    private Map<String, Object> publicPayload(AgentRunPart part) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partId", part.getPartId());
        payload.put("messageId", part.getMessageId());
        payload.put("partKey", part.getPartKey());
        payload.put("partType", part.getPartType());
        payload.put("status", part.getStatus());
        payload.put("toolCallId", part.getToolCallId());
        payload.put("tool", part.getToolName());
        payload.put("input", part.getInputJson());
        payload.put("output", part.getOutputText());
        payload.put("metadata", part.getMetadata());
        payload.put("sequence", part.getSequenceNumber());
        payload.put("createdAt", part.getCreateTime());
        payload.put("updatedAt", part.getUpdateTime());
        return payload;
    }

    private String limit(String value) {
        if (value == null) return "";
        return value.length() <= 8_000 ? value : value.substring(0, 8_000) + "\n...truncated...";
    }
}