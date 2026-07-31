package com.labex.labexagent.run;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.labex.entity.AgentRunArtifact;
import com.labex.entity.AgentRunPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 记录一次工具调用的可恢复生命周期，兼容旧 artifact 并同步写入统一 Part。
 * Part 是未来历史回放的主要事实来源，artifact 继续作为兼容和诊断数据保留。
 */
@Service
public class AgentToolCallJournalService {
    private static final String ARTIFACT_TYPE = "tool_call_state";
    private static final Gson GSON = new Gson();

    private final AgentRunArtifactService artifactService;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunPartService partService;

    @Autowired
    public AgentToolCallJournalService(AgentRunArtifactService artifactService,
                                       AgentRunLifecycleService lifecycleService,
                                       AgentRunPartService partService) {
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService is required");
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

    public List<AgentRunArtifact> history(Long taskId, String toolCallId) {
        return artifactService.list(taskId, ARTIFACT_TYPE).stream()
                .filter(artifact -> toolCallId != null && toolCallId.equals(artifact.getArtifactPath()))
                .toList();
    }

    public List<Map<String, Object>> latestForTask(Long taskId) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (AgentRunArtifact artifact : artifactService.list(taskId, ARTIFACT_TYPE)) {
            if (artifact.getArtifactPath() == null || artifact.getArtifactPath().isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> state = GSON.fromJson(artifact.getContent(), Map.class);
                if (state != null) latest.put(artifact.getArtifactPath(), state);
            } catch (JsonParseException ignored) {
                // ?????? artifact??? durable Part ????????
            }
        }
        return latest.values().stream().toList();
    }

    private void recordExisting(Long taskId, String toolCallId, String status, String detail) {
        Map<String, Object> current = latestForTask(taskId).stream()
                .filter(state -> toolCallId != null && toolCallId.equals(String.valueOf(state.get("toolCallId"))))
                .findFirst()
                .orElse(Map.of());
        if (!current.isEmpty()) {
            Object rawIteration = current.get("iteration");
            int iteration = rawIteration instanceof Number number ? number.intValue() : 0;
            record(taskId, toolCallId, status, String.valueOf(current.getOrDefault("tool", "tool")),
                    current.getOrDefault("arguments", Map.of()), iteration, detail);
            return;
        }
        AgentRunPart part = partService.resolveExistingToolCall(taskId, toolCallId, status, detail);
        if (part != null) {
            lifecycleService.appendEvent(taskId, "TOOL_CALL_STATE", Map.of(
                    "taskId", taskId,
                    "toolCallId", toolCallId,
                    "tool", part.getToolName() == null ? "tool" : part.getToolName(),
                    "status", status,
                    "detail", detail == null ? "" : truncate(detail),
                    "partId", part.getPartId(),
                    "partKey", part.getPartKey()),
                    "tool-call-state-part-" + part.getPartId() + "-" + status);
        }
    }

    private void record(Long taskId, String toolCallId, String status, String toolName,
                        Object arguments, int iteration, String detail) {
        record(taskId, toolCallId, status, toolName, arguments, iteration, detail, Map.of());
    }

    private void record(Long taskId, String toolCallId, String status, String toolName,
                        Object arguments, int iteration, String detail, Map<String, Object> extraPayload) {
        if (taskId == null || toolCallId == null || toolCallId.isBlank()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolCallId", toolCallId);
        payload.put("status", status);
        payload.put("tool", toolName == null ? "" : toolName);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        payload.put("iteration", iteration);
        payload.put("detail", detail == null ? "" : truncate(detail));
        if (extraPayload != null && !extraPayload.isEmpty()) {
            payload.put("interactionPayload", new LinkedHashMap<>(extraPayload));
        }

        AgentRunPart part = partService.upsertToolCall(taskId, toolCallId, status, toolName,
                arguments, iteration, detail);
        AgentRunArtifact artifact = artifactService.recordDeterministic(
                taskId, ARTIFACT_TYPE, toolCallId, GSON.toJson(payload));

        Map<String, Object> event = new LinkedHashMap<>(payload);
        event.put("taskId", taskId);
        event.put("artifactId", artifact.getArtifactId());
        event.put("partId", part.getPartId());
        event.put("partKey", part.getPartKey());
        String eventKey = "tool-call-state-part-" + part.getPartId() + "-" + status;
        lifecycleService.appendEvent(taskId, "TOOL_CALL_STATE", event, eventKey);
    }

    private String truncate(String value) {
        return value.length() <= 4_000 ? value : value.substring(0, 4_000) + "\n...truncated...";
    }
}
