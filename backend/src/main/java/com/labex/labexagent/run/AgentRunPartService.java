package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.labexagent.runtime.AgentToolCallIdPolicy;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final AgentRunExecutionLeaseService leaseService;
    private final AgentToolOutputProperties toolOutputProperties;

    @Autowired
    public AgentRunPartService(AgentRunPartMapper partMapper, AgentTaskMapper taskMapper,
                               AgentRunMessageService messageService,
                               AgentRunExecutionLeaseService leaseService,
                               AgentToolOutputProperties toolOutputProperties) {
        this.partMapper = partMapper;
        this.taskMapper = taskMapper;
        this.messageService = messageService;
        this.leaseService = leaseService;
        this.toolOutputProperties = toolOutputProperties == null ? new AgentToolOutputProperties() : toolOutputProperties;
    }

    public AgentRunPartService(AgentRunPartMapper partMapper, AgentTaskMapper taskMapper,
                               AgentRunMessageService messageService,
                               AgentRunExecutionLeaseService leaseService) {
        this(partMapper, taskMapper, messageService, leaseService, new AgentToolOutputProperties());
    }

    public AgentRunPartService(AgentRunPartMapper partMapper, AgentTaskMapper taskMapper,
                               AgentRunMessageService messageService) {
        this(partMapper, taskMapper, messageService,
                new AgentRunExecutionLeaseService(taskMapper, "legacy-instance", 30_000L));
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunPart upsertToolCall(Long taskId, String toolCallId, String status, String toolName,
                                       Object arguments, int iteration, String detail) {
        if (taskId == null || taskId <= 0 || !AgentToolCallIdPolicy.isValid(toolCallId)) {
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
                "tool:" + toolCallId, "tool", status, toolCallId,
                toolName, arguments, detail, iteration);
    }

    /**
     * Executor-fenced Tool Part 写入：先验证 {@link ExecutionFence}（owner + 精确 epoch + 未过期 lease），
     * stale fence 抛出 typed failure，Part 与 transcript message 均不被写入。
     * 该预检在写入事务内、任何 INSERT/UPDATE 之前执行；lifecycle/plan 的 fenced 写入另将
     * owner/epoch/active-lease 嵌入 UPDATE 谓词。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunPart upsertToolCall(ExecutionFence fence, Long taskId, String toolCallId, String status,
                                       String toolName, Object arguments, int iteration, String detail) {
        requireFence(fence);
        return upsertToolCall(taskId, toolCallId, status, toolName, arguments, iteration, detail);
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
            providerCall.setOutputText(detail);
            providerCall.setUpdateTime(now);
            partMapper.updateById(providerCall);
        }
    }

    /** 更新已存在的工具 Part，用于命令审批等延后终态的持久化。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunPart resolveExistingToolCall(Long taskId, String toolCallId, String status, String detail) {
        if (taskId == null || taskId <= 0 || !AgentToolCallIdPolicy.isValid(toolCallId)) return null;
        syncProviderToolCallState(taskId, toolCallId, status, detail);
        AgentRunPart part = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .eq(AgentRunPart::getPartType, "tool")
                .last("LIMIT 1"));
        if (part == null) {
            part = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                    .eq(AgentRunPart::getTaskId, taskId)
                    .eq(AgentRunPart::getToolCallId, toolCallId)
                    .eq(AgentRunPart::getPartType, "tool_call")
                    .last("LIMIT 1"));
        }
        if (part == null) return null;
        part.setStatus(status == null || status.isBlank() ? "error" : status);
        part.setOutputText(detail);
        part.setUpdateTime(LocalDateTime.now());
        partMapper.updateById(part);
        if (part.getSequenceNumber() != null) {
            String messageStatus = "completed".equals(part.getStatus()) || "skipped".equals(part.getStatus())
                    ? "completed" : "error";
            messageService.upsertAssistantTurn(taskId, part.getSequenceNumber(), messageStatus);
        }
        return part;
    }

    /**
     * Executor-fenced 已存在 Tool Part 更新：先验证 {@link ExecutionFence}，stale fence 抛出 typed failure。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunPart resolveExistingToolCall(ExecutionFence fence, Long taskId, String toolCallId,
                                                String status, String detail) {
        requireFence(fence);
        return resolveExistingToolCall(taskId, toolCallId, status, detail);
    }

    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public AgentRunPart recordEventPart(Long taskId, String eventType, Object payload, long sequence) {
        if (taskId == null || eventType == null || eventType.isBlank() || !supportsEventPart(eventType)) return null;
        Map<String, Object> data = payload instanceof Map<?, ?> map
                ? copyMap(map) : new LinkedHashMap<>(Map.of("value", payload == null ? "" : payload));
        sanitizeReasoningPayload(eventType, data);
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
            case "RUN_PROGRESS_MIGRATED" ->
                    upsertPart(taskId, messageId, "progress:migration", "progress", "completed",
                            null, null, data, GSON.toJson(data), sequence);
            case "LOOP_GUARD_PROGRESS" ->
                    upsertPart(taskId, messageId, "loop-guard:progress", "loop_guard_progress", "completed",
                            null, null, data, GSON.toJson(data), sequence);
            case "FINALIZATION_BLOCKED" -> {
                String fingerprint = text(data, "evidenceFingerprint");
                boolean recoveryAllowed = Boolean.TRUE.equals(data.get("recoveryAllowed"));
                yield upsertPart(taskId, messageId, "finalization:" + (fingerprint.isBlank() ? sequence : fingerprint),
                        "finalization_blocker", recoveryAllowed ? "waiting" : "error", null, null,
                        data, GSON.toJson(data), sequence);
            }
            case "MODEL_STEP_STARTED", "MODEL_STEP_COMPLETED", "MODEL_STEP_FAILED",
                 "MODEL_STEP_BLOCKED", "MODEL_STEP_INTERRUPTED" ->
                    upsertPart(taskId, messageId, "model-step:" + eventIteration(data), "model_step",
                            modelStepStatus(eventType), null, null, data, GSON.toJson(data), sequence);
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
            case "RUN_MODEL_RETRY_SCHEDULED", "RUN_MODEL_RETRY_STARTED" ->
                    upsertPart(taskId, messageId, "retry:" + sequence, "retry",
                            eventType.endsWith("SCHEDULED") ? "waiting" : "running",
                            null, null, data, GSON.toJson(data), sequence);
            case "RUN_RECOVERY_TAKEOVER", "RUN_STATE_RECOVERING" ->
                    upsertPart(taskId, messageId, "recovery:" + sequence, "recovery",
                            "running", null, null, data, GSON.toJson(data), sequence);
            case "CONFIG_PROPOSAL_CREATED" ->
                    upsertPart(taskId, messageId, "config-proposal:" + sequence, "config_proposal",
                            "waiting", null, null, data, GSON.toJson(data), sequence);
            case "CONFIG_PROPOSAL_DECIDED", "CONFIG_REVISION_APPLIED" ->
                    upsertPart(taskId, messageId, "config-proposal:" + sequence, "config_proposal",
                            "completed", null, null, data, GSON.toJson(data), sequence);
            case "CONFIG_PROPOSAL_FAILED" ->
                    upsertPart(taskId, messageId, "config-proposal:" + sequence, "config_proposal",
                            "error", null, null, data, GSON.toJson(data), sequence);
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
                part.setOutputText(detail);
            }
            part.setUpdateTime(LocalDateTime.now());
            updated += partMapper.updateById(part) == 1 ? 1 : 0;
        }
        return updated;
    }

    /**
     * Reads a bounded raw-output page only when the active task, student, and project all match.
     * Offsets use Java character indices but never split a Unicode surrogate pair.
     */
    @Transactional(readOnly = true)
    public ToolOutputSlice readOwnedToolOutput(Integer studentId, Integer projectId, Long taskId,
                                                String toolCallId, int offset, int requestedLimit) {
        if (studentId == null || projectId == null || taskId == null || taskId <= 0
                || !AgentToolCallIdPolicy.isValid(toolCallId)) {
            throw new IllegalArgumentException("tool output is unavailable for the active task");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        AgentRunPart part = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .eq(AgentRunPart::getPartType, "tool")
                .last("LIMIT 1"));
        if (part == null || !Objects.equals(studentId, part.getStudentId())
                || !Objects.equals(projectId, part.getProjectId())
                || !AgentToolCallIdPolicy.isValid(part.getToolCallId())
                || !Objects.equals(toolCallId, part.getToolCallId())) {
            throw new IllegalArgumentException("tool output is unavailable for the active task");
        }

        String raw = part.getOutputText() == null ? "" : part.getOutputText();
        if (offset > raw.length()) {
            throw new IllegalArgumentException("offset is outside the stored tool output");
        }
        if (offset < raw.length() && offset > 0 && Character.isLowSurrogate(raw.charAt(offset))
                && Character.isHighSurrogate(raw.charAt(offset - 1))) {
            throw new IllegalArgumentException("offset must not split a Unicode surrogate pair");
        }
        if (requestedLimit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        int limit = requestedLimit == 0 ? toolOutputProperties.getReadMaxChars()
                : Math.min(requestedLimit, toolOutputProperties.getReadMaxChars());
        int candidateEnd = Math.min(raw.length(), offset + limit);
        if (candidateEnd < raw.length() && candidateEnd > offset
                && Character.isHighSurrogate(raw.charAt(candidateEnd - 1))
                && Character.isLowSurrogate(raw.charAt(candidateEnd))) {
            candidateEnd++;
        }
        return new ToolOutputSlice(part.getToolName(), toolCallId, offset, candidateEnd,
                raw.length(), raw.substring(offset, candidateEnd));
    }

    public record ToolOutputSlice(String toolName, String toolCallId, int offset, int nextOffset,
                                  int totalChars, String content) {
        public boolean hasMore() {
            return nextOffset < totalChars;
        }
    }

    public List<AgentRunPart> history(Long taskId) {
        if (taskId == null || taskId <= 0) return List.of();
        return partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .orderByAsc(AgentRunPart::getPartId));
    }

    public List<AgentRunPart> currentEpochToolHistory(Long taskId, long executionEpoch) {
        if (taskId == null || taskId <= 0 || executionEpoch < 0) return List.of();
        return history(taskId).stream()
                .filter(this::isTerminalToolPart)
                .filter(part -> belongsToEpoch(part, executionEpoch))
                .toList();
    }

    private boolean isTerminalToolPart(AgentRunPart part) {
        if (part == null || !"tool".equals(part.getPartType())) {
            return false;
        }
        String status = part.getStatus() == null ? "" : part.getStatus().trim().toLowerCase(Locale.ROOT);
        return "completed".equals(status) || "error".equals(status) || "blocked".equals(status)
                || "skipped".equals(status) || "interrupted".equals(status);
    }

    private boolean belongsToEpoch(AgentRunPart part, long expectedEpoch) {
        if (part == null || part.getMetadata() == null || part.getMetadata().isBlank()) {
            return false;
        }
        try {
            Object parsed = GSON.fromJson(part.getMetadata(), Object.class);
            if (!(parsed instanceof Map<?, ?> metadata)) {
                return false;
            }
            Object raw = metadata.get("executionEpoch");
            if (raw instanceof Number number) {
                return number.longValue() == expectedEpoch;
            }
            return raw != null && Long.parseLong(String.valueOf(raw)) == expectedEpoch;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public int currentEpochFinalizationRecoveryAttempts(Long taskId, long executionEpoch, String evidenceFingerprint) {
        if (taskId == null || taskId <= 0 || executionEpoch < 0 || evidenceFingerprint == null
                || evidenceFingerprint.isBlank()) {
            return 0;
        }
        return history(taskId).stream()
                .filter(part -> part != null && "finalization_blocker".equals(part.getPartType()))
                .filter(part -> belongsToEpoch(part, executionEpoch))
                .filter(part -> evidenceFingerprint.equals(eventText(part, "evidenceFingerprint")))
                .max(Comparator.comparingLong(part -> part.getSequenceNumber() == null ? 0L : part.getSequenceNumber()))
                .map(part -> eventNonNegativeInteger(part, "recoveryAttempt"))
                .orElse(0);
    }

    private String eventText(AgentRunPart part, String field) {
        Object value = eventValue(part, field);
        return value == null ? "" : String.valueOf(value);
    }

    private int eventNonNegativeInteger(AgentRunPart part, String field) {
        Object value = eventValue(part, field);
        if (value instanceof Number number) {
            return Math.max(0, Math.min(100_000, number.intValue()));
        }
        try {
            return Math.max(0, Math.min(100_000, Integer.parseInt(String.valueOf(value))));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private Object eventValue(AgentRunPart part, String field) {
        if (part == null || part.getInputJson() == null || part.getInputJson().isBlank()) {
            return null;
        }
        try {
            Object parsed = GSON.fromJson(part.getInputJson(), Object.class);
            if (!(parsed instanceof Map<?, ?> data)) {
                return null;
            }
            return data.get(field);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public int currentEpochLoopGuardProgress(Long taskId, long executionEpoch) {
        if (taskId == null || taskId <= 0 || executionEpoch < 0) return 0;
        return history(taskId).stream()
                .filter(part -> part != null && "loop_guard_progress".equals(part.getPartType()))
                .filter(part -> belongsToEpoch(part, executionEpoch))
                .max(Comparator.comparingLong(part -> part.getSequenceNumber() == null ? 0L : part.getSequenceNumber()))
                .map(this::nonProgressIterations)
                .orElse(0);
    }

    private int nonProgressIterations(AgentRunPart part) {
        if (part == null || part.getInputJson() == null || part.getInputJson().isBlank()) return 0;
        try {
            Object parsed = GSON.fromJson(part.getInputJson(), Object.class);
            if (!(parsed instanceof Map<?, ?> data)) return 0;
            Object value = data.get("nonProgressIterations");
            if (value instanceof Number number) return Math.max(0, Math.min(100_000, number.intValue()));
            return value == null ? 0 : Math.max(0, Math.min(100_000, Integer.parseInt(String.valueOf(value))));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public List<Map<String, Object>> publicHistory(Long taskId) {
        return history(taskId).stream().map(this::publicPayload).toList();
    }

    /** 按任务批量读取公开 Part 投影，避免历史页重复查询与组装 DTO。 */
    public Map<Long, List<Map<String, Object>>> publicHistoryByTaskIds(Collection<Long> taskIds) {
        return publicHistoryByTaskIds(taskIds, 0);
    }

    /**
     * 按任务批量读取公开 Part 投影；当 {@code maxOutputChars > 0} 时，工具输出超出部分会被
     * 截断并附加 {@code outputTruncated}/{@code outputLength} 标记（历史浏览用），
     * 任务恢复等完整路径不受影响。
     */
    public Map<Long, List<Map<String, Object>>> publicHistoryByTaskIds(Collection<Long> taskIds,
                                                                        int maxOutputChars) {
        List<Long> ids = taskIds == null ? List.of() : taskIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        List<AgentRunPart> stored = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .in(AgentRunPart::getTaskId, ids)
                .orderByAsc(AgentRunPart::getTaskId)
                .orderByAsc(AgentRunPart::getPartId));
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (AgentRunPart part : stored == null ? List.<AgentRunPart>of() : stored) {
            if (part == null || part.getTaskId() == null) continue;
            grouped.computeIfAbsent(part.getTaskId(), ignored -> new java.util.ArrayList<>())
                    .add(publicPayload(part, maxOutputChars));
        }
        grouped.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(grouped);
    }

    /**
     * 为旧客户端保留 toolCalls DTO，但数据只从持久化 Tool Part 派生。
     */
    public List<Map<String, Object>> publicToolCalls(Long taskId) {
        return history(taskId).stream()
                .filter(part -> "tool".equals(part.getPartType()))
                .filter(part -> part.getToolCallId() != null && !part.getToolCallId().isBlank())
                .map(this::projectToolCall)
                .toList();
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
        part.setOutputText(output);
        part.setSequenceNumber(sequence);
        long executionEpoch = task == null || task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
        part.setMetadata(GSON.toJson(Map.of(
                "sequence", sequence,
                "partType", partType,
                "status", part.getStatus(),
                "executionEpoch", executionEpoch)));
        part.setUpdateTime(now);
        if (part.getPartId() == null) {
            partMapper.insert(part);
        } else {
            partMapper.updateById(part);
        }
        return part;
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

    private long eventIteration(Map<String, Object> data) {
        Object raw = data.get("iteration");
        if (raw instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return raw == null ? 0L : Math.max(0L, Long.parseLong(String.valueOf(raw)));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String modelStepStatus(String eventType) {
        return switch (eventType) {
            case "MODEL_STEP_STARTED" -> "running";
            case "MODEL_STEP_COMPLETED" -> "completed";
            case "MODEL_STEP_FAILED" -> "error";
            case "MODEL_STEP_BLOCKED" -> "blocked";
            case "MODEL_STEP_INTERRUPTED" -> "interrupted";
            default -> "unknown";
        };
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
                 "COMMAND_EXECUTION_INTERRUPTED", "RUN_MODEL_RETRY_SCHEDULED",
                 "RUN_MODEL_RETRY_STARTED", "RUN_RECOVERY_TAKEOVER",
                 "RUN_STATE_RECOVERING", "RUN_PROGRESS_MIGRATED", "LOOP_GUARD_PROGRESS", "FINALIZATION_BLOCKED",
                 "MODEL_STEP_STARTED", "MODEL_STEP_COMPLETED", "MODEL_STEP_FAILED",
                 "MODEL_STEP_BLOCKED", "MODEL_STEP_INTERRUPTED", "DONE", "RUN_STATE_COMPLETED",
                 "RUN_STATE_FAILED",
                 "CONFIG_PROPOSAL_CREATED", "CONFIG_PROPOSAL_DECIDED",
                 "CONFIG_REVISION_APPLIED", "CONFIG_PROPOSAL_FAILED" -> true;
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

    Map<String, Object> projectToolCall(AgentRunPart part) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partId", part.getPartId());
        payload.put("partKey", part.getPartKey());
        payload.put("toolCallId", part.getToolCallId());
        payload.put("tool", part.getToolName() == null ? "" : part.getToolName());
        payload.put("arguments", structuredInput(part.getInputJson()));
        payload.put("status", part.getStatus() == null ? "unknown" : part.getStatus());
        payload.put("iteration", part.getSequenceNumber() == null ? 0L : part.getSequenceNumber());
        payload.put("detail", part.getOutputText() == null ? "" : part.getOutputText());
        return payload;
    }

    private Object structuredInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) return Map.of();
        try {
            Object value = GSON.fromJson(inputJson, Object.class);
            return value == null ? Map.of() : value;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> publicPayload(AgentRunPart part) {
        return publicPayload(part, 0);
    }

    private Map<String, Object> publicPayload(AgentRunPart part, int maxOutputChars) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partId", part.getPartId());
        payload.put("messageId", part.getMessageId());
        payload.put("partKey", part.getPartKey());
        payload.put("partType", part.getPartType());
        payload.put("status", part.getStatus());
        payload.put("toolCallId", part.getToolCallId());
        payload.put("tool", part.getToolName());
        payload.put("input", part.getInputJson());
        Map<String, Object> output = publicOutput(part, maxOutputChars);
        payload.put("output", output.get("text"));
        if (output.get("truncated") == Boolean.TRUE) {
            payload.put("outputTruncated", true);
            payload.put("outputLength", output.get("length"));
        }
        payload.put("metadata", part.getMetadata());
        payload.put("sequence", part.getSequenceNumber());
        payload.put("createdAt", part.getCreateTime());
        payload.put("updatedAt", part.getUpdateTime());
        return payload;
    }

    private Map<String, Object> publicOutput(AgentRunPart part, int maxOutputChars) {
        String type = part.getPartType() == null ? "" : part.getPartType();
        String text;
        if ("text".equalsIgnoreCase(type)) {
            text = InternalReasoningBoundary.stripVisible(part.getOutputText());
        } else if ("reasoning".equalsIgnoreCase(type)) {
            text = InternalReasoningBoundary.stripTags(part.getOutputText());
        } else {
            text = part.getOutputText();
        }
        if (maxOutputChars > 0 && text != null && text.length() > maxOutputChars) {
            return Map.of("text", text.substring(0, maxOutputChars), "truncated", true,
                    "length", text.length());
        }
        return Map.of("text", text, "truncated", false);
    }

    private void requireFence(ExecutionFence fence) {
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for executor-originated writes");
        }
        leaseService.requireActiveFence(fence, LocalDateTime.now());
    }
}