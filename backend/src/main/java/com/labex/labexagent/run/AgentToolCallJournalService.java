package com.labex.labexagent.run;

import com.google.gson.Gson;
import com.labex.entity.AgentRunArtifact;
import com.labex.entity.AgentRunPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public AgentToolCallJournalService(AgentRunArtifactService artifactService,
                                       AgentRunLifecycleService lifecycleService) {
        this(artifactService, lifecycleService, null);
    }

    @Autowired
    public AgentToolCallJournalService(AgentRunArtifactService artifactService,
                                       AgentRunLifecycleService lifecycleService,
                                       AgentRunPartService partService) {
        this.artifactService = artifactService;
        this.lifecycleService = lifecycleService;
        this.partService = partService;
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
        Map<String, Object> payload = new LinkedHashMap<>();
        if (interactionPayload != null) payload.putAll(interactionPayload);
        payload.put("requestId", requestId == null ? "" : requestId);
        payload.put("interactionType", "question");
        record(taskId, toolCallId, "waiting_user", toolName, arguments, iteration, detail, payload);
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
            } catch (RuntimeException ignored) {
                // 工具调用状态会落到现有运行产物，刷新后可以恢复 pending 和终态。
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
        if (partService != null) {
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

        AgentRunArtifact artifact = null;
        try {
            artifact = artifactService.recordDeterministic(
                    taskId, ARTIFACT_TYPE, toolCallId, GSON.toJson(payload));
        } catch (RuntimeException ignored) {
            // 兼容旧数据库或 artifact 写入失败时，仍然继续尝试写入新的 Part。
        }

        AgentRunPart part = null;
        try {
            if (partService != null) {
                part = partService.upsertToolCall(taskId, toolCallId, status, toolName,
                        arguments, iteration, detail);
            }
        } catch (RuntimeException ignored) {
            // Part 写入失败不能反向破坏工具执行；任务事件仍然提供恢复线索。
        }

        try {
            Map<String, Object> event = new LinkedHashMap<>(payload);
            event.put("taskId", taskId);
            if (artifact != null) event.put("artifactId", artifact.getArtifactId());
            if (part != null) {
                event.put("partId", part.getPartId());
                event.put("partKey", part.getPartKey());
            }
            String eventKey;
            if (part != null && part.getPartId() != null) {
                eventKey = "tool-call-state-part-" + part.getPartId() + "-" + status;
            } else if (artifact != null && artifact.getArtifactId() != null) {
                eventKey = "tool-call-state-" + artifact.getArtifactId();
            } else {
                eventKey = "tool-call-state-" + toolCallId + "-" + status + "-" + iteration;
            }
            lifecycleService.appendEvent(taskId, "TOOL_CALL_STATE", event, eventKey);
        } catch (RuntimeException ignored) {
            // 日志记录不能反向破坏工具执行；SSE 和任务状态仍然由主流程负责。
        }
    }

    private String truncate(String value) {
        return value.length() <= 4_000 ? value : value.substring(0, 4_000) + "\n...truncated...";
    }
}