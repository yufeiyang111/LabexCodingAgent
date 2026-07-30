package com.labex.labexagent.run;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AgentRunResumeScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentRunResumeScheduler.class);
    private final AgentTaskService taskService;
    private final AgentLoopEngine agentLoopEngine;

    public AgentRunResumeScheduler(AgentTaskService taskService, @Lazy AgentLoopEngine agentLoopEngine) {
        this.taskService = taskService;
        this.agentLoopEngine = agentLoopEngine;
    }

    public boolean resumeIfWaiting(AgentRunInteraction interaction) {
        if (!isResolvedForResume(interaction)) {
            log.info("AGENT_INTERACTION_RESUME_SKIPPED interactionId={} type={} status={}",
                    interaction == null ? null : interaction.getInteractionId(),
                    interaction == null ? null : interaction.getInteractionType(),
                    interaction == null ? null : interaction.getStatus());
            return false;
        }
        AgentTask task = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (task == null || !isWaiting(task)) {
            log.info("AGENT_INTERACTION_RESUME_SKIPPED interactionId={} taskId={} taskStatus={}",
                    interaction.getInteractionId(), interaction.getTaskId(), task == null ? null : task.getStatus());
            return false;
        }

        AgentStreamRequest request = continuationRequest(task, interaction);
        request.setResumeInteractionId(interaction.getInteractionId());
        if (!taskService.beginInteractionResume(
                task.getTaskId(),
                interaction.getInteractionId(),
                "Resuming after user response",
                "A persisted user response is ready")) {
            log.warn("AGENT_INTERACTION_RESUME_TRANSITION_REJECTED interactionId={} taskId={} taskStatus={}",
                    interaction.getInteractionId(), task.getTaskId(), task.getStatus());
            return false;
        }
        AgentTask resumedTask = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (resumedTask == null || !"recovering".equals(resumedTask.getStatus())) {
            log.warn("AGENT_INTERACTION_RESUME_STATE_MISMATCH interactionId={} taskId={} resumedStatus={}",
                    interaction.getInteractionId(), task.getTaskId(), resumedTask == null ? null : resumedTask.getStatus());
            return false;
        }
        try {
            log.info("AGENT_INTERACTION_RESUME_ENQUEUED interactionId={} taskId={} continuationChars={}",
                    interaction.getInteractionId(), resumedTask.getTaskId(), request.getMessage() == null ? 0 : request.getMessage().length());
            agentLoopEngine.resume(resumedTask.getStudentId(), resumedTask.getProjectId(), request, resumedTask.getTaskId(), true);
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
            case "permission", "network" -> "approved".equals(interaction.getStatus()) || "rejected".equals(interaction.getStatus());
            default -> false;
        };
    }

    private boolean isWaiting(AgentTask task) {
        return "waiting_user".equals(task.getStatus()) || "waiting_approval".equals(task.getStatus());
    }

    private AgentStreamRequest continuationRequest(AgentTask task, AgentRunInteraction interaction) {
        String continuation;
        if ("network".equals(interaction.getInteractionType())
                && interaction.getRequestPayload() != null
                && interaction.getRequestPayload().contains("offline_failure_retry")) {
            String retryInstruction = "approved".equals(interaction.getStatus())
                    ? "Network approval was granted. Retry exactly the command in the original payload once, with the tool argument network=true; do not change the command or run a different command."
                    : "Network approval was not granted. Do not retry the failed command with network access; reassess the workspace and use a safe offline alternative.";
            continuation = """
                    A pending user interaction has been resolved.
                    Interaction type: %s
                    Resolution status: %s
                    Original interaction payload: %s
                    User response payload: %s
                    %s
                    """.formatted(
                    interaction.getInteractionType(),
                    interaction.getStatus(),
                    compact(interaction.getRequestPayload()),
                    compact(interaction.getResponsePayload()),
                    retryInstruction);
        } else {
            continuation = """
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
                    compact(interaction.getResponsePayload()));
        }
        return AgentRunContinuationRequestFactory.fromTask(task, continuation);
    }

    private String compact(String payload) {
        if (payload == null || payload.isBlank()) {
            return "{}";
        }
        return payload.length() <= 4_000 ? payload : payload.substring(0, 4_000) + "...";
    }
}
