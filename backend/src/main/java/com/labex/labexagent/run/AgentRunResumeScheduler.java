package com.labex.labexagent.run;

import com.google.gson.Gson;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.commandsecurity.CommandApprovalOrchestrator;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AgentRunResumeScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentRunResumeScheduler.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_CLAIM_ATTEMPTS = 350;
    private static final long CLAIM_RETRY_DELAY_MS = 100L;
    private static final String RESUME_STEP = "Resuming after user response";
    private static final String RESUME_SUMMARY = "A persisted user response is ready";
    private static final String FAILURE_STEP = "Unable to resume after user response";
    private static final String EXHAUSTED_SUMMARY =
            "The previous execution lease did not become available after the persisted interaction response";
    private static final int RECONCILE_BATCH_SIZE = 100;

    private final AgentTaskService taskService;
    private final AgentLoopEngine agentLoopEngine;
    private final AgentRunInteractionService interactionService;
    private final CommandApprovalOrchestrator commandApprovalOrchestrator;
    private final Executor networkRetryExecutor;
    private final Set<String> scheduledRetries = ConcurrentHashMap.newKeySet();
    private final Set<String> scheduledNetworkRetries = ConcurrentHashMap.newKeySet();
    private CommandApprovalService commandApprovalService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCommandApprovalService(CommandApprovalService commandApprovalService) {
        this.commandApprovalService = commandApprovalService;
    }

    public AgentRunResumeScheduler(AgentTaskService taskService, @Lazy AgentLoopEngine agentLoopEngine) {
        this(taskService, agentLoopEngine, null, null, Runnable::run);
    }

    public AgentRunResumeScheduler(AgentTaskService taskService,
                                   @Lazy AgentLoopEngine agentLoopEngine,
                                   AgentRunInteractionService interactionService) {
        this(taskService, agentLoopEngine, interactionService, null, Runnable::run);
    }

    public AgentRunResumeScheduler(AgentTaskService taskService,
                                   @Lazy AgentLoopEngine agentLoopEngine,
                                   AgentRunInteractionService interactionService,
                                   @Lazy CommandApprovalOrchestrator commandApprovalOrchestrator) {
        this(taskService, agentLoopEngine, interactionService, commandApprovalOrchestrator, ForkJoinPool.commonPool());
    }

    @Autowired
    public AgentRunResumeScheduler(AgentTaskService taskService,
                                   @Lazy AgentLoopEngine agentLoopEngine,
                                   AgentRunInteractionService interactionService,
                                   @Lazy CommandApprovalOrchestrator commandApprovalOrchestrator,
                                   @Qualifier(AgentRunExecutorConfiguration.NETWORK_RETRY_EXECUTOR) Executor networkRetryExecutor) {
        this.taskService = taskService;
        this.agentLoopEngine = agentLoopEngine;
        this.interactionService = interactionService;
        this.commandApprovalOrchestrator = commandApprovalOrchestrator;
        this.networkRetryExecutor = networkRetryExecutor;
    }

    @Scheduled(fixedDelayString = "${labex-agent.interaction-resume-poll-interval-ms:1000}")
    public void reconcileScheduled() {
        resumeResolvedInteractions();
    }

    public int resumeResolvedInteractions() {
        if (interactionService == null) {
            return 0;
        }
        int accepted = 0;
        for (AgentRunInteraction interaction : interactionService.findResolvedAwaitingResume(RECONCILE_BATCH_SIZE)) {
            try {
                if (resumeIfWaiting(interaction)) {
                    accepted++;
                }
            } catch (RuntimeException failure) {
                log.error("AGENT_INTERACTION_RECONCILE_FAILED interactionId={} taskId={}",
                        interaction == null ? null : interaction.getInteractionId(),
                        interaction == null ? null : interaction.getTaskId(), failure);
            }
        }
        return accepted;
    }

    public boolean resumeIfWaiting(AgentRunInteraction interaction) {
        if (isOfflineNetworkRetry(interaction) && commandApprovalOrchestrator != null
                && commandApprovalOrchestrator.canResumeOfflineNetworkRetry(interaction)) {
            return scheduleOfflineNetworkRetry(interaction);
        }
        if (!isResolvedForResume(interaction)) {
            log.info("AGENT_INTERACTION_RESUME_SKIPPED interactionId={} type={} status={}",
                    interaction == null ? null : interaction.getInteractionId(),
                    interaction == null ? null : interaction.getInteractionType(),
                    interaction == null ? null : interaction.getStatus());
            return false;
        }
        return attemptResume(interaction, 1);
    }

    /**
     * 网络批准只负责持久化决策并入队；真实命令不能占用 HTTP 审批请求线程。
     */
    private boolean scheduleOfflineNetworkRetry(AgentRunInteraction interaction) {
        if (commandApprovalOrchestrator == null) {
            log.error("NETWORK_RETRY_RESUME_UNAVAILABLE interactionId={} taskId={} reason=orchestrator_missing",
                    interaction.getInteractionId(), interaction.getTaskId());
            return false;
        }
        String retryKey = interaction.getInteractionId();
        if (retryKey == null || retryKey.isBlank()) {
            retryKey = interaction.getTaskId() + ":" + interaction.getStatus() + ":" + interaction.getRequestPayload();
        }
        String scheduledKey = retryKey;
        if (!scheduledNetworkRetries.add(scheduledKey)) {
            log.info("NETWORK_RETRY_RESUME_COALESCED interactionId={} taskId={}",
                    interaction.getInteractionId(), interaction.getTaskId());
            return true;
        }
        try {
            networkRetryExecutor.execute(() -> {
                try {
                    boolean resumed = commandApprovalOrchestrator.resumeOfflineNetworkRetry(interaction);
                    if (!resumed) {
                        log.warn("NETWORK_RETRY_RESUME_DEFERRED interactionId={} taskId={}",
                                interaction.getInteractionId(), interaction.getTaskId());
                    }
                } catch (RuntimeException failure) {
                    log.error("NETWORK_RETRY_RESUME_FAILED interactionId={} taskId={}",
                            interaction.getInteractionId(), interaction.getTaskId(), failure);
                } finally {
                    scheduledNetworkRetries.remove(scheduledKey);
                }
            });
            log.info("NETWORK_RETRY_RESUME_ENQUEUED interactionId={} taskId={}",
                    interaction.getInteractionId(), interaction.getTaskId());
            return true;
        } catch (RuntimeException schedulingFailure) {
            scheduledNetworkRetries.remove(scheduledKey);
            log.error("NETWORK_RETRY_RESUME_QUEUE_REJECTED interactionId={} taskId={}",
                    interaction.getInteractionId(), interaction.getTaskId(), schedulingFailure);
            return false;
        }
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
                    interaction.getInteractionId(), task.getTaskId(), task.getStatus(), alreadyProgressed);
            return alreadyProgressed;
        }
        if (hasPendingCommandExecution(task)) {
            // 已批准的命令审批执行中/待执行：该路径拥有任务恢复权，交互恢复必须让位，否则会在命令执行中途误判失败。
            log.info("AGENT_INTERACTION_RESUME_YIELDED_TO_COMMAND interactionId={} taskId={}",
                    interaction.getInteractionId(), task.getTaskId());
            return true;
        }

        AgentStreamRequest request = continuationRequest(task, interaction);
        AgentRunLifecycleService.InteractionClaimOutcome outcome = taskService.claimResolvedInteractionDispatch(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId(),
                interaction.getInteractionId(), RESUME_STEP, RESUME_SUMMARY);
        if (!outcome.claimed()) {
            if (outcome.outcome() == AgentRunLifecycleService.InteractionClaimOutcome.Outcome.DEFERRED) {
                return deferClaim(interaction, task, attempt);
            }
            log.info("AGENT_INTERACTION_RESUME_REJECTED interactionId={} taskId={} outcome={}",
                    interaction.getInteractionId(), task.getTaskId(), outcome.outcome());
            return false;
        }
        AgentRunLifecycleService.InteractionClaimOutcome claim = outcome;
        AgentTask resumedTask = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (resumedTask == null || !"recovering".equals(resumedTask.getStatus())) {
            log.warn("AGENT_INTERACTION_RESUME_STATE_MISMATCH interactionId={} taskId={} resumedStatus={}",
                    interaction.getInteractionId(), task.getTaskId(), resumedTask == null ? null : resumedTask.getStatus());
            return false;
        }
        try {
            log.info("AGENT_INTERACTION_RESUME_ENQUEUED interactionId={} taskId={} continuationChars={} attempt={} claimId={}",
                    interaction.getInteractionId(), resumedTask.getTaskId(),
                    request.getMessage() == null ? 0 : request.getMessage().length(), attempt,
                    claim.interaction().getResumeClaimId());
            agentLoopEngine.resume(resumedTask.getStudentId(), resumedTask.getProjectId(), request,
                    resumedTask.getTaskId(), true, claim.lease(), claim.interaction());
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
        if (hasPendingCommandExecution(current)) {
            log.info("AGENT_INTERACTION_RESUME_FAIL_DEFERRED interactionId={} taskId={} reason=command_execution_active",
                    interaction.getInteractionId(), current.getTaskId());
            return;
        }
        taskService.updateTask(
                current.getTaskId(), "failed", FAILURE_STEP, summary, failureKey(interaction, reason));
    }

    private boolean hasPendingCommandExecution(AgentTask task) {
        return commandApprovalService != null
                && task != null
                && task.getTaskId() != null
                && commandApprovalService.hasPendingExecution(task.getTaskId());
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
            case "permission", "network" -> "approved".equals(interaction.getStatus())
                    || "rejected".equals(interaction.getStatus())
                    || "timed_out".equals(interaction.getStatus());
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
        String resolutionNote = "timed_out".equals(interaction.getStatus())
                ? "The interaction expired without a user decision. Treat the waiting action as not approved and choose a different approach."
                : "";
        String continuation = """
                A pending user interaction has been resolved.
                Interaction type: %s
                Resolution status: %s
                %s
                Original interaction payload: %s
                User response payload: %s
                Do not automatically repeat the tool call that was waiting for interaction. Reassess the current workspace and choose the next safe action.
                """.formatted(
                interaction.getInteractionType(),
                interaction.getStatus(),
                resolutionNote,
                compact(interaction.getRequestPayload()),
                compact(interaction.getResponsePayload()));
        return AgentRunContinuationRequestFactory.fromTask(task, continuation);
    }

    private boolean isOfflineNetworkRetry(AgentRunInteraction interaction) {
        if (interaction == null || !"network".equals(interaction.getInteractionType())
                || interaction.getRequestPayload() == null || interaction.getRequestPayload().isBlank()) {
            return false;
        }
        try {
            Map<?, ?> payload = GSON.fromJson(interaction.getRequestPayload(), Map.class);
            return "offline_failure_retry".equals(String.valueOf(payload == null ? null : payload.get("requestKind")));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String compact(String payload) {
        if (payload == null || payload.isBlank()) {
            return "{}";
        }
        return payload.length() <= 4_000 ? payload : payload.substring(0, 4_000) + "...";
    }
}
