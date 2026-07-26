package com.labex.labexagent.run;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AgentRunResumeScheduler {
    private final AgentTaskService taskService;
    private final AgentLoopEngine agentLoopEngine;

    public AgentRunResumeScheduler(AgentTaskService taskService, @Lazy AgentLoopEngine agentLoopEngine) {
        this.taskService = taskService;
        this.agentLoopEngine = agentLoopEngine;
    }

    public boolean resumeIfWaiting(AgentRunInteraction interaction) {
        if (!isResolvedForResume(interaction)) {
            return false;
        }
        AgentTask task = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (task == null || !isWaiting(task)) {
            return false;
        }

        AgentStreamRequest request = continuationRequest(task, interaction);
        if (!taskService.beginInteractionResume(
                task.getTaskId(),
                "Resuming after user response",
                "A persisted user response is ready")) {
            return false;
        }
        try {
            agentLoopEngine.resume(task.getStudentId(), task.getProjectId(), request, task.getTaskId(), true);
            return true;
        } catch (RuntimeException exception) {
            taskService.updateTask(task.getTaskId(), "failed", "Unable to resume after user response",
                    exception.getMessage() == null ? "Unable to enqueue Agent continuation" : exception.getMessage());
            return false;
        }
    }

    private boolean isResolvedForResume(AgentRunInteraction interaction) {
        if (interaction == null || interaction.getInteractionType() == null || interaction.getStatus() == null) {
            return false;
        }
        return switch (interaction.getInteractionType()) {
            case "question" -> "answered".equals(interaction.getStatus()) || "cancelled".equals(interaction.getStatus());
            case "permission" -> "approved".equals(interaction.getStatus()) || "rejected".equals(interaction.getStatus());
            default -> false;
        };
    }

    private boolean isWaiting(AgentTask task) {
        return "waiting_user".equals(task.getStatus()) || "waiting_approval".equals(task.getStatus());
    }

    private AgentStreamRequest continuationRequest(AgentTask task, AgentRunInteraction interaction) {
        AgentStreamRequest request = new AgentStreamRequest();
        request.setSessionId(task.getSessionId());
        request.setConversationId(task.getConversationId());
        request.setMode(task.getMode());
        request.setResumeTaskId(task.getTaskId());
        request.setMessage("""
                Continue the existing task from its durable conversation history.
                A pending user interaction has been resolved.
                Interaction type: %s
                Resolution status: %s
                Original interaction payload: %s
                User response payload: %s
                Do not automatically repeat the tool call that was waiting for interaction. Reassess the current workspace and choose the next safe action.
                """.formatted(
                interaction.getInteractionType(),
                interaction.getStatus(),
                compact(interaction.getRequestPayload()),
                compact(interaction.getResponsePayload())));
        return request;
    }

    private String compact(String payload) {
        if (payload == null || payload.isBlank()) {
            return "{}";
        }
        return payload.length() <= 4_000 ? payload : payload.substring(0, 4_000) + "...";
    }
}
