package com.labex.labexagent.run;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AgentRunResumeScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentRunResumeScheduler.class);
    private static final int MAX_CLAIM_ATTEMPTS = 350;
    private static final long CLAIM_RETRY_DELAY_MS = 100L;
    private static final String RESUME_STEP = "Resuming after user response";
    private static final String RESUME_SUMMARY = "A persisted user response is ready";
    private static final String FAILURE_STEP = "Unable to resume after user response";
    private static final String EXHAUSTED_SUMMARY =
            "The previous execution lease did not become available after the persisted interaction response";

    private final AgentTaskService taskService;
    private final AgentLoopEngine agentLoopEngine;
    private final Set<String> scheduledRetries = ConcurrentHashMap.newKeySet();

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
        return attemptResume(interaction, 1);
    }

    private boolean attemptResume(AgentRunInteraction interaction, int attempt) {
        AgentTask task = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (task == null) {
            log.info("AGENT_INTERACTION_RESUME_SKIPPED interactionId={} taskId={} taskStatus=null",
                    interaction.getInteractionId(), interaction.getTaskId());
            return false;
        }
        if (!isWaiting(task)) {
            boolean alreadyProgressed = isAlreadyProgressing(task);
            log.info("AGENT_INTERACTION_RESUME_SKIPPED interactionId={} taskId={} taskStatus={} alreadyProgressed={}",
                    interaction.getInteractionId(), interaction.getTaskId(), task.getStatus(), alreadyProgressed);
            return alreadyProgressed;
        }

        AgentStreamRequest request = continuationRequest(task, interaction);
        request.setResumeInteractionId(interaction.getInteractionId());
        AgentRunLifecycleService.DispatchClaim claim = taskService.claimInteractionResume(
                task.getTaskId(), interaction.getInteractionId(), RESUME_STEP, RESUME_SUMMARY);
        if (claim == null) {
            return deferClaim(interaction, task, attempt);
        }
        AgentTask resumedTask = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (resumedTask == null || !"recovering".equals(resumedTask.getStatus())) {
            log.warn("AGENT_INTERACTION_RESUME_STATE_MISMATCH interactionId={} taskId={} resumedStatus={}",
                    interaction.getInteractionId(), task.getTaskId(), resumedTask == null ? null : resumedTask.getStatus());
            return false;
        }
        try {
            log.info("AGENT_INTERACTION_RESUME_ENQUEUED interactionId={} taskId={} continuationChars={} attempt={}",
                    interaction.getInteractionId(), resumedTask.getTaskId(),
                    request.getMessage() == null ? 0 : request.getMessage().length(), attempt);
            agentLoopEngine.resume(resumedTask.getStudentId(), resumedTask.getProjectId(), request,
                    resumedTask.getTaskId(), true, claim.lease());
            return true;
        } catch (RuntimeException exception) {
            taskService.updateTask(task.getTaskId(), "failed", FAILURE_STEP,
                    exception.getMessage() == null ? "Unable to enqueue Agent continuation" : exception.getMessage(),
                    failureKey(interaction, "dispatch"));
            return false;
        }
    }

    private boolean deferClaim(AgentRunInteraction interaction, AgentTask task, int attempt) {
        if (attempt >= MAX_CLAIM_ATTEMPTS) {
            failIfStillWaiting(interaction, EXHAUSTED_SUMMARY, "claim-exhausted");
            log.error("AGENT_INTERACTION_RESUME_CLAIM_EXHAUSTED interactionId={} taskId={} attempts={}",
                    interaction.getInteractionId(), task.getTaskId(), attempt);
            return false;
        }

        boolean newlyScheduled = scheduledRetries.add(interaction.getInteractionId());
        if (newlyScheduled) {
            int nextAttempt = attempt + 1;
            CompletableFuture.delayedExecutor(CLAIM_RETRY_DELAY_MS, TimeUnit.MILLISECONDS).execute(() -> {
                scheduledRetries.remove(interaction.getInteractionId());
                try {
                    attemptResume(interaction, nextAttempt);
                } catch (RuntimeException exception) {
                    log.error("AGENT_INTERACTION_RESUME_RETRY_FAILED interactionId={} taskId={} attempt={}",
                            interaction.getInteractionId(), interaction.getTaskId(), nextAttempt, exception);
                    failIfStillWaiting(
                            interaction,
                            exception.getMessage() == null
                                    ? "Unexpected failure while retrying the persisted interaction continuation"
                                    : exception.getMessage(),
                            "retry-failed");
                }
            });
        }
        log.info("AGENT_INTERACTION_RESUME_RETRY_SCHEDULED interactionId={} taskId={} attempt={} nextAttempt={} newlyScheduled={}",
                interaction.getInteractionId(), task.getTaskId(), attempt, attempt + 1, newlyScheduled);
        return true;
    }

    private void failIfStillWaiting(AgentRunInteraction interaction, String summary, String reason) {
        AgentTask current = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (current == null || !isWaiting(current)) {
            return;
        }
        taskService.updateTask(
                current.getTaskId(), "failed", FAILURE_STEP, summary, failureKey(interaction, reason));
    }

    private String failureKey(AgentRunInteraction interaction, String reason) {
        return "interaction-resume-" + reason + "-" + interaction.getInteractionId();
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

    private boolean isAlreadyProgressing(AgentTask task) {
        return "recovering".equals(task.getStatus())
                || "preparing".equals(task.getStatus())
                || "running".equals(task.getStatus())
                || "completed".equals(task.getStatus());
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
