package com.labex.labexagent.run;

import com.labex.entity.AgentRunPart;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 记录工具调用的可恢复生命周期。
 *
 * <p>Tool Part 是唯一持久化事实，事件仅作为可重放投影；旧兼容工件不再读取或写入。</p>
 *
 * <p>执行器发起的写入（{@code pending/running/waitingApproval/waitingInteraction/completed/failed/blocked/skipped}）
 * 必须携带 {@link ExecutionFence}，经 fenced Part/事件 overload 验证 owner + epoch + 未过期 lease，
 * stale fence 抛出 typed failure 且不发生任何持久化。控制面写入
 * （{@code completedExisting/failedExisting/interruptedExisting}，仅由审批过期/调度接管路径调用）
 * 按 {@link ExecutionFence} 契约不参与该 fence。</p>
 *
 * <p>Part upsert 与事件追加是两个独立事务：fence 在各自 writer 内分别验证，若两者之间 lease 过期，
 * 事件投影可能被跳过（事件只是 Part 的可重放投影，不构成部分完成）。</p>
 */
@Service
public class AgentToolCallJournalService {
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunPartService partService;

    @Autowired
    public AgentToolCallJournalService(AgentRunLifecycleService lifecycleService,
                                       AgentRunPartService partService) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService is required");
        this.partService = Objects.requireNonNull(partService, "partService is required");
    }

    public void pending(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments, int iteration) {
        record(fence, taskId, toolCallId, "pending", toolName, arguments, iteration, "");
    }

    public void running(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments, int iteration) {
        record(fence, taskId, toolCallId, "running", toolName, arguments, iteration, "");
    }

    public void waitingApproval(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                                int iteration, String approvalId) {
        record(fence, taskId, toolCallId, "waiting_approval", toolName, arguments, iteration,
                approvalId == null ? "" : approvalId);
    }

    public void waitingUser(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                            int iteration, String requestId, String detail, Map<String, Object> interactionPayload) {
        waitingInteraction(fence, taskId, toolCallId, toolName, arguments, iteration, requestId,
                "question", detail, interactionPayload);
    }

    /**
     * 根据交互类型持久化正确的等待状态和交互载荷。
     */
    public void waitingInteraction(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                                   int iteration, String requestId, String interactionType, String detail,
                                   Map<String, Object> interactionPayload) {
        String type = "permission".equals(interactionType) || "network".equals(interactionType)
                ? interactionType : "question";
        String status = "question".equals(type) ? "waiting_user" : "waiting_approval";
        Map<String, Object> payload = new LinkedHashMap<>();
        if (interactionPayload != null) payload.putAll(interactionPayload);
        payload.put("requestId", requestId == null ? "" : requestId);
        payload.put("interactionType", type);
        record(fence, taskId, toolCallId, status, toolName, arguments, iteration, detail, payload);
    }

    public void completed(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                          int iteration, String result) {
        record(fence, taskId, toolCallId, "completed", toolName, arguments, iteration, result);
    }

    public void failed(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                        int iteration, String result) {
        record(fence, taskId, toolCallId, "error", toolName, arguments, iteration, result);
    }

    /** 用户取消或执行被中断的工具调用，持久化为 interrupted（OpenCode 用 metadata 标记中断，这里用显式状态）。 */
    public void interrupted(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                            int iteration, String result) {
        record(fence, taskId, toolCallId, "interrupted", toolName, arguments, iteration, result);
    }

    public void blocked(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                        int iteration, String result) {
        record(fence, taskId, toolCallId, "environment_blocked", toolName, arguments, iteration, result);
    }

    public void skipped(ExecutionFence fence, Long taskId, String toolCallId, String toolName, Object arguments,
                        int iteration, String reason) {
        record(fence, taskId, toolCallId, "skipped", toolName, arguments, iteration, reason);
    }

    /** 控制面写入：审批过期/调度接管路径，不携带执行 fence（见 {@link ExecutionFence} 契约）。 */
    public void completedExisting(Long taskId, String toolCallId, String result) {
        recordExisting(taskId, toolCallId, "completed", result);
    }

    /** 控制面写入：审批过期/调度接管路径，不携带执行 fence（见 {@link ExecutionFence} 契约）。 */
    public void failedExisting(Long taskId, String toolCallId, String result) {
        recordExisting(taskId, toolCallId, "error", result);
    }

    /** 控制面写入：审批过期/调度接管路径，不携带执行 fence（见 {@link ExecutionFence} 契约）。 */
    public void interruptedExisting(Long taskId, String toolCallId, String result) {
        recordExisting(taskId, toolCallId, "interrupted", result);
    }

    private void recordExisting(Long taskId, String toolCallId, String status, String detail) {
        AgentRunPart part = partService.resolveExistingToolCall(taskId, toolCallId, status, detail);
        if (part == null) return;
        Map<String, Object> payload = new LinkedHashMap<>(partService.projectToolCall(part));
        payload.put("detail", detail == null ? "" : truncate(detail));
        publishPartEvent(null, taskId, part, status, payload);
    }

    private void record(ExecutionFence fence, Long taskId, String toolCallId, String status, String toolName,
                        Object arguments, int iteration, String detail) {
        record(fence, taskId, toolCallId, status, toolName, arguments, iteration, detail, Map.of());
    }

    private void record(ExecutionFence fence, Long taskId, String toolCallId, String status, String toolName,
                        Object arguments, int iteration, String detail, Map<String, Object> extraPayload) {
        if (taskId == null || toolCallId == null || toolCallId.isBlank()) return;
        Map<String, Object> payload = basePayload(toolCallId, status, toolName, arguments, iteration, detail);
        if (extraPayload != null && !extraPayload.isEmpty()) {
            payload.put("interactionPayload", new LinkedHashMap<>(extraPayload));
        }

        AgentRunPart part = Objects.requireNonNull(
                partService.upsertToolCall(fence, taskId, toolCallId, status, toolName,
                        arguments, iteration, detail),
                "Durable Tool Part persistence returned null");
        publishPartEvent(fence, taskId, part, status, payload);
    }

    private Map<String, Object> basePayload(String toolCallId, String status, String toolName,
                                            Object arguments, int iteration, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolCallId == null ? "" : toolCallId);
        payload.put("status", status == null ? "unknown" : status);
        payload.put("tool", toolName == null ? "" : toolName);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        payload.put("iteration", iteration);
        payload.put("detail", detail == null ? "" : truncate(detail));
        return payload;
    }

    private void publishPartEvent(ExecutionFence fence, Long taskId, AgentRunPart part, String status,
                                  Map<String, Object> payload) {
        payload.put("taskId", taskId);
        payload.put("partId", part.getPartId());
        payload.put("partKey", part.getPartKey());
        String eventKey = "tool-call-state-part-" + part.getPartId() + "-" + status;
        if (fence == null) {
            lifecycleService.appendEvent(taskId, "TOOL_CALL_STATE", payload, eventKey);
        } else {
            lifecycleService.appendEvent(fence, taskId, "TOOL_CALL_STATE", payload, eventKey);
        }
    }

    private String truncate(String value) {
        return value.length() <= 4_000 ? value : value.substring(0, 4_000) + "\n...truncated...";
    }
}
