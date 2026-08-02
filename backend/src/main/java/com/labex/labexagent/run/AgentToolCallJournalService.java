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

    public void pending(Long taskId, String toolCallId, String toolName, Object arguments, int iteration) {
        record(taskId, toolCallId, "pending", toolName, arguments, iteration, "");
    }

    public void running(Long taskId, String toolCallId, String toolName, Object arguments, int iteration) {
        record(taskId, toolCallId, "running", toolName, arguments, iteration, "");
    }

    public void waitingApproval(Long taskId, String toolCallId, String toolName, Object arguments,
                                int iteration, String approvalId) {
        record(taskId, toolCallId, "waiting_approval", toolName, arguments, iteration,
                approvalId == null ? "" : approvalId);
    }

    public void waitingUser(Long taskId, String toolCallId, String toolName, Object arguments,
                            int iteration, String requestId, String detail, Map<String, Object> interactionPayload) {
        waitingInteraction(taskId, toolCallId, toolName, arguments, iteration, requestId,
                "question", detail, interactionPayload);
    }

    /**
     * 根据交互类型持久化正确的等待状态和交互载荷。
     */
    public void waitingInteraction(Long taskId, String toolCallId, String toolName, Object arguments,
                                   int iteration, String requestId, String interactionType, String detail,
                                   Map<String, Object> interactionPayload) {
        String type = "permission".equals(interactionType) || "network".equals(interactionType)
                ? interactionType : "question";
        String status = "question".equals(type) ? "waiting_user" : "waiting_approval";
        Map<String, Object> payload = new LinkedHashMap<>();
        if (interactionPayload != null) payload.putAll(interactionPayload);
        payload.put("requestId", requestId == null ? "" : requestId);
        payload.put("interactionType", type);
        record(taskId, toolCallId, status, toolName, arguments, iteration, detail, payload);
    }

    public void completed(Long taskId, String toolCallId, String toolName, Object arguments,
                          int iteration, String result) {
        record(taskId, toolCallId, "completed", toolName, arguments, iteration, result);
    }

    public void failed(Long taskId, String toolCallId, String toolName, Object arguments,
                       int iteration, String result) {
        record(taskId, toolCallId, "error", toolName, arguments, iteration, result);
    }

    public void blocked(Long taskId, String toolCallId, String toolName, Object arguments,
                        int iteration, String result) {
        record(taskId, toolCallId, "environment_blocked", toolName, arguments, iteration, result);
    }

    public void skipped(Long taskId, String toolCallId, String toolName, Object arguments,
                        int iteration, String reason) {
        record(taskId, toolCallId, "skipped", toolName, arguments, iteration, reason);
    }

    public void completedExisting(Long taskId, String toolCallId, String result) {
        recordExisting(taskId, toolCallId, "completed", result);
    }

    public void failedExisting(Long taskId, String toolCallId, String result) {
        recordExisting(taskId, toolCallId, "error", result);
    }

    private void recordExisting(Long taskId, String toolCallId, String status, String detail) {
        AgentRunPart part = partService.resolveExistingToolCall(taskId, toolCallId, status, detail);
        if (part == null) return;
        Map<String, Object> payload = new LinkedHashMap<>(partService.projectToolCall(part));
        payload.put("detail", detail == null ? "" : truncate(detail));
        publishPartEvent(taskId, part, status, payload);
    }

    private void record(Long taskId, String toolCallId, String status, String toolName,
                        Object arguments, int iteration, String detail) {
        record(taskId, toolCallId, status, toolName, arguments, iteration, detail, Map.of());
    }

    private void record(Long taskId, String toolCallId, String status, String toolName,
                        Object arguments, int iteration, String detail, Map<String, Object> extraPayload) {
        if (taskId == null || toolCallId == null || toolCallId.isBlank()) return;
        Map<String, Object> payload = basePayload(toolCallId, status, toolName, arguments, iteration, detail);
        if (extraPayload != null && !extraPayload.isEmpty()) {
            payload.put("interactionPayload", new LinkedHashMap<>(extraPayload));
        }

        AgentRunPart part = Objects.requireNonNull(
                partService.upsertToolCall(taskId, toolCallId, status, toolName,
                        arguments, iteration, detail),
                "Durable Tool Part persistence returned null");
        publishPartEvent(taskId, part, status, payload);
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

    private void publishPartEvent(Long taskId, AgentRunPart part, String status,
                                  Map<String, Object> payload) {
        payload.put("taskId", taskId);
        payload.put("partId", part.getPartId());
        payload.put("partKey", part.getPartKey());
        String eventKey = "tool-call-state-part-" + part.getPartId() + "-" + status;
        lifecycleService.appendEvent(taskId, "TOOL_CALL_STATE", payload, eventKey);
    }

    private String truncate(String value) {
        return value.length() <= 4_000 ? value : value.substring(0, 4_000) + "\n...truncated...";
    }
}
