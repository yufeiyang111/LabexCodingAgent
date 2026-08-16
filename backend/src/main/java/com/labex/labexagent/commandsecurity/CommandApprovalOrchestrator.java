package com.labex.labexagent.commandsecurity;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunLeaseHeartbeatService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.AgentVerificationRecorder;
import com.labex.labexagent.run.CommandFailureGuard;
import com.labex.labexagent.run.EnvironmentBlockerClassifier;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.workspace.WorkspaceOperationIdentity;
import com.labex.labexagent.run.AgentRunState;
import com.labex.labexagent.network.NetworkAccessService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Coordinates one-time command approval decisions and execution, then resumes the durable Agent
 * loop with the redacted command outcome. It owns lifecycle transitions; controllers only authenticate and serialize its results.
 */
@Service
public class CommandApprovalOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(CommandApprovalOrchestrator.class);
    private final CommandApprovalService approvalService;
    private final CommandAuditService auditService;
    private final AgentApprovedCommandExecutor executor;
    private final StudentProjectService projectService;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentProjectMetadataRefreshScheduler metadataRefreshScheduler;
    private final CommandApprovalResumeScheduler commandResumeScheduler;
    private final AgentToolCallJournalService toolCallJournalService;
    private final AgentRunTranscriptService transcriptService;
    private final AgentCancellationRegistry cancellationRegistry;
    private final AgentTaskService taskService;
    private CommandFailureGuard commandFailureGuard = new CommandFailureGuard(1, 2);
    private final NetworkAccessService networkAccessService;
    private AgentRunExecutionLeaseService executionLeaseService;
    private AgentRunLeaseHeartbeatService leaseHeartbeatService;
    private AgentVerificationRecorder verificationRecorder;
    private GitSnapshotService snapshotService;
    private DiffService diffService;

    @org.springframework.beans.factory.annotation.Autowired
    public CommandApprovalOrchestrator(CommandApprovalService approvalService, CommandAuditService auditService,
                                       AgentApprovedCommandExecutor executor, StudentProjectService projectService,
                                       AgentRunLifecycleService lifecycleService,
                                       AgentProjectMetadataRefreshScheduler metadataRefreshScheduler,
                                       CommandApprovalResumeScheduler commandResumeScheduler,
                                       NetworkAccessService networkAccessService,
                                       AgentToolCallJournalService toolCallJournalService,
                                       AgentRunTranscriptService transcriptService,
                                       AgentCancellationRegistry cancellationRegistry,
                                       AgentTaskService taskService) {
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.executor = executor;
        this.projectService = projectService;
        this.lifecycleService = lifecycleService;
        this.metadataRefreshScheduler = metadataRefreshScheduler;
        this.commandResumeScheduler = Objects.requireNonNull(commandResumeScheduler,
                "commandResumeScheduler is required for durable command approval continuation");
        this.networkAccessService = networkAccessService;
        this.toolCallJournalService = Objects.requireNonNull(toolCallJournalService,
                "toolCallJournalService is required for durable command approval continuation");
        this.transcriptService = Objects.requireNonNull(transcriptService,
                "transcriptService is required for durable command approval continuation");
        this.cancellationRegistry = Objects.requireNonNull(cancellationRegistry,
                "cancellationRegistry is required for approved command cancellation");
        this.taskService = Objects.requireNonNull(taskService,
                "taskService is required for approved command cancellation finalization");
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCommandFailureGuard(CommandFailureGuard commandFailureGuard) {
        if (commandFailureGuard != null) this.commandFailureGuard = commandFailureGuard;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCommandExecutionLeaseServices(AgentRunExecutionLeaseService executionLeaseService,
                                          AgentRunLeaseHeartbeatService leaseHeartbeatService) {
        this.executionLeaseService = executionLeaseService;
        this.leaseHeartbeatService = leaseHeartbeatService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVerificationRecorder(AgentVerificationRecorder verificationRecorder) {
        this.verificationRecorder = verificationRecorder;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkspaceChangeEvidenceServices(GitSnapshotService snapshotService, DiffService diffService) {
        this.snapshotService = snapshotService;
        this.diffService = diffService;
    }

    public DecisionResult decide(Integer studentId, Integer projectId, String approvalId,
                                 boolean approve, String decisionIdempotencyKey) {
        CommandApproval approval = ownedAgentApproval(studentId, projectId, approvalId);
        if (approval == null || !isLatestTaskApproval(approval)) {
            log.warn("COMMAND_APPROVAL_DECISION_UNAVAILABLE projectId={} approvalId={} reason=superseded_or_missing",
                    projectId, approvalId);
            return DecisionResult.unavailable();
        }
        long startedNanos = System.nanoTime();
        log.info("COMMAND_APPROVAL_DECISION_REQUEST_RECEIVED taskId={} projectId={} approvalId={} approve={}",
                approval.getTaskId(), projectId, approval.getApprovalId(), approve);
        CommandApproval decided = approvalService.decide(studentId, projectId, approvalId, approve,
                decisionIdempotencyKey);
        if (!"agent_shell".equals(decided.getSource())) {
            return DecisionResult.unavailable();
        }
        String status = decided.getStatus();
        boolean resumeAgentLoop = false;
        if ("rejected".equals(status) || "expired".equals(status)) {
            resumeAgentLoop = resolveRejected(decided, status, decisionIdempotencyKey);
        } else {
            lifecycleService.appendEvent(decided.getTaskId(), "COMMAND_APPROVAL_DECIDED",
                    publicPayload(decided, Map.of("decision", status, "resumeAgentLoop", false)),
                    lifecycleKey(decided, "decision:" + decisionIdempotencyKey));
        }
        log.info("COMMAND_APPROVAL_DECISION_PERSISTED taskId={} projectId={} approvalId={} status={} resumeAgentLoop={} elapsedMs={}",
                decided.getTaskId(), projectId, decided.getApprovalId(), status, resumeAgentLoop, elapsedMs(startedNanos));
        return DecisionResult.available(decided, resumeAgentLoop);
    }

    public ExecutionResult execute(Integer studentId, Integer projectId, String approvalId) {
        long requestStartedNanos = System.nanoTime();
        StudentProject project = projectService.getOwnedProject(studentId, projectId);
        CommandApproval approval = ownedAgentApproval(studentId, projectId, approvalId);
        if (project == null || approval == null || !isLatestTaskApproval(approval)) {
            log.warn("COMMAND_APPROVAL_EXECUTION_UNAVAILABLE projectId={} approvalId={} reason=superseded_or_missing",
                    projectId, approvalId);
            return ExecutionResult.unavailable();
        }
        AgentRunExecutionLeaseService.ExecutionLease executionLease = acquireExecutionLease(approval);
        if (executionLeaseService != null && executionLease == null) {
            log.warn("COMMAND_APPROVAL_EXECUTION_UNAVAILABLE taskId={} projectId={} approvalId={} reason=execution_lease_unavailable",
                    approval.getTaskId(), projectId, approvalId);
            return ExecutionResult.unavailable();
        }
        if (!consume(approval)) {
            releaseExecutionLease(executionLease);
            log.warn("COMMAND_APPROVAL_EXECUTION_UNAVAILABLE projectId={} approvalId={} reason=already_consumed",
                    projectId, approvalId);
            return ExecutionResult.unavailable();
        }
        log.info("COMMAND_APPROVAL_EXECUTION_REQUEST_ACCEPTED taskId={} projectId={} approvalId={} workingDirectory={} requestElapsedMs={}",
                approval.getTaskId(), projectId, approval.getApprovalId(), approval.getWorkingDirectory(), elapsedMs(requestStartedNanos));
        approval.setStatus("consumed");
        AgentCancellationRegistry.ActiveRun activeRun = null;
        try {
            if (leaseHeartbeatService != null && executionLease != null) {
                leaseHeartbeatService.track(executionLease, approval.getSessionId());
            }
            activeRun = cancellationRegistry.register(approval.getSessionId(), studentId, projectId, approval.getTaskId());
            lifecycleService.appendEvent(approval.getTaskId(), "COMMAND_EXECUTION_STARTED",
                    publicPayload(approval, Map.of("resumeAgentLoop", false)),
                    lifecycleKey(approval, "execution-started"));
            auditService.recordExecutionStarted(approval);
            log.info("COMMAND_APPROVAL_PROCESS_STARTED taskId={} projectId={} approvalId={} workingDirectory={}",
                    approval.getTaskId(), projectId, approval.getApprovalId(), approval.getWorkingDirectory());
            GitSnapshotService.Snapshot beforeWorkspaceSnapshot = captureWorkspaceSnapshot(
                    project, approval, "before approved command ");
            long processStartedNanos = System.nanoTime();
            ProcessExecutionResult result = executor.execute(approval, project, activeRun,
                    identity -> recordExecutionProcessBound(approval, identity));
            long orchestrationDurationMs = elapsedMs(processStartedNanos);
            long processDurationMs = result.durationMs();
            WorkspaceChangeEvidence workspaceEvidence = recordWorkspaceChangeEvidence(
                    approval, project, beforeWorkspaceSnapshot);
            if (result.status() == ExecutionStatus.CANCELLED) {
                return completeCancelledExecution(approval, result, studentId, projectId,
                        processDurationMs, orchestrationDurationMs, requestStartedNanos, workspaceEvidence);
            }
            auditService.recordExecutionOutcome(approval, result, processDurationMs);
            recordVerificationOutcome(approval, result, "approved_command");
            boolean succeeded = result.succeeded();
            if (!succeeded && shouldRequestNetworkRetry(approval, result)) {
                NetworkAccessService.NetworkAccessRequest network = createNetworkRetry(approval, result);
                if (network != null) {
                    Map<String, Object> payload = new LinkedHashMap<>(network.payload());
                    payload.put("requestId", network.requestId());
                    payload.put("taskId", approval.getTaskId());
                    payload.put("sessionId", approval.getSessionId());
                    payload.put("toolCallId", approval.getToolCallId());
                    payload.put("toolName", approvalToolName(approval.getCanonicalCommand()));
                    payload.put("approvalId", approval.getApprovalId());
                    lifecycleService.transition(approval.getTaskId(), AgentRunState.WAITING_APPROVAL,
                            "NETWORK_ACCESS_ASK", payload, "\u7b49\u5f85\u7f51\u7edc\u8bbf\u95ee\u6279\u51c6",
                            "\u68c0\u6d4b\u5230\u79bb\u7ebf\u7f51\u7edc\u5931\u8d25\uff0c\u6279\u51c6\u540e\u53ea\u91cd\u8bd5\u5f53\u524d\u547d\u4ee4\u4e00\u6b21",
                            lifecycleKey(approval, "network-retry-approval"));
                    return ExecutionResult.available(approval, result, "waiting_network");
                }
            }
            if (!succeeded) {
                EnvironmentBlockerClassifier.classify(approvalToolName(approval.getCanonicalCommand()),
                                ToolResult.fromProcessExecution(result))
                        .ifPresent(blocker -> lifecycleService.appendEvent(approval.getTaskId(), "ENVIRONMENT_BLOCKED",
                                publicPayload(approval, Map.of("blockerCode", blocker.code(), "code", blocker.code(),
                                        "detail", blocker.detail(), "retryable", false, "manualRetryRequired", true)),
                                lifecycleKey(approval, "environment-blocked:" + blocker.code())));
            }
            if (!succeeded) {
                commandFailureGuard.record(approval.getTaskId(), approvalToolName(approval.getCanonicalCommand()),
                        approval.getCanonicalCommand(), approval.getWorkingDirectory(), result.output());
            }
            boolean resumeAgentLoop = isLatestTaskApproval(approval);
            String executionStatus = succeeded ? "completed" : "failed";
            log.info("COMMAND_APPROVAL_PROCESS_FINISHED taskId={} projectId={} approvalId={} executionStatus={} exitCode={} processDurationMs={} orchestrationDurationMs={}",
                    approval.getTaskId(), projectId, approval.getApprovalId(), executionStatus,
                    result.exitCode(), processDurationMs, orchestrationDurationMs);
            lifecycleService.appendEvent(approval.getTaskId(),
                    succeeded ? "COMMAND_EXECUTION_COMPLETED" : "COMMAND_EXECUTION_FAILED",
                    publicPayload(approval, Map.of(
                            "executionStatus", executionStatus,
                            "exitCode", result.exitCode() == null ? "" : result.exitCode(),
                            "durationMs", processDurationMs,
                            "resumeAgentLoop", resumeAgentLoop)),
                    lifecycleKey(approval, "execution-outcome:" + executionStatus));
            if (succeeded || workspaceEvidence.hasChanges()) {
                // 刷新事实必须携带 snapshot 产生的目标身份；失败命令若已部分改动工作区也需要刷新，
                // 但 executionStatus 仍保持 failed，不能被 UI 或模型误投影为完成。
                lifecycleService.appendEvent(approval.getTaskId(), "WORKSPACE_CHANGED",
                        workspaceChangedPayload(approval, executionStatus, workspaceEvidence, resumeAgentLoop),
                        lifecycleKey(approval, "workspace-changed"));
            }
            closeApprovedToolCall(approval, succeeded, result, workspaceEvidence);
            releaseExecutionLease(executionLease);
            executionLease = null;
            if (resumeAgentLoop) {
                resumeAgentLoop = resumeAgentLoop(approval, executionStatus, result);
            } else {
                log.info("COMMAND_APPROVAL_AGENT_RESUME_SKIPPED taskId={} projectId={} approvalId={} reason=superseded",
                        approval.getTaskId(), projectId, approval.getApprovalId());
            }
            metadataRefreshScheduler.schedule(studentId, projectId, "command_approval");
            log.info("COMMAND_APPROVAL_EXECUTION_HTTP_RETURNED taskId={} projectId={} approvalId={} executionStatus={} resumeAgentLoop={} totalElapsedMs={}",
                    approval.getTaskId(), projectId, approval.getApprovalId(), executionStatus, resumeAgentLoop,
                    elapsedMs(requestStartedNanos));
            return ExecutionResult.available(approval, result, resumeAgentLoop ? "resuming" : executionStatus);
        } catch (Exception exception) {
            try {
                auditService.recordExecutionInterrupted(approval, "orchestration_failure");
                failApprovedToolCall(approval, "Approved command orchestration was interrupted");
                lifecycleService.transition(approval.getTaskId(), AgentRunState.FAILED,
                        "COMMAND_EXECUTION_INTERRUPTED",
                        publicPayload(approval, Map.of("executionStatus", "interrupted", "resumeAgentLoop", false)),
                        "Approved command interrupted", "Execution outcome requires inspection; the command will not be replayed.",
                        lifecycleKey(approval, "execution-interrupted"));
            } catch (Exception ignored) {
                // The consumed capability must remain non-replayable even if finalization is unavailable.
            }
            return ExecutionResult.unavailable();
        } finally {
            cancellationRegistry.complete(activeRun);
            releaseExecutionLease(executionLease);
        }
    }

    /**
     * 网络批准后由服务端精确重试已持久化的原命令，并把结果写回原 toolCallId。
     */
    public boolean resumeOfflineNetworkRetry(AgentRunInteraction interaction) {
        NetworkAccessService.OfflineRetryDescriptor retry = networkAccessService == null
                ? null : networkAccessService.offlineRetryDescriptor(interaction);
        if (retry == null) return false;
        CommandApproval approval = offlineNetworkRetryApproval(interaction, retry);
        if (approval == null) {
            failNetworkRetryProtocol(interaction, "The persisted network retry no longer matches its original command approval");
            return false;
        }
        AgentTask task = taskService.getOwnedTask(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
        if (task == null) return false;
        if (!"waiting_approval".equals(task.getStatus())) {
            return "recovering".equals(task.getStatus()) || "preparing".equals(task.getStatus())
                    || "running".equals(task.getStatus()) || "completed".equals(task.getStatus());
        }
        if ("rejected".equals(interaction.getStatus()) || "timed_out".equals(interaction.getStatus())) {
            String detail = "status=failed\nnetwork_approval=" + interaction.getStatus()
                    + "\nThe command was not retried.";
            lifecycleService.appendEvent(approval.getTaskId(), "NETWORK_RETRY_REJECTED",
                    publicPayload(approval, Map.of("interactionId", interaction.getInteractionId(),
                            "resumeAgentLoop", true)), lifecycleKey(approval, "network-retry-rejected"));
            resolveApprovedToolCall(approval, detail);
            resumeAgentLoop(approval, "network_" + interaction.getStatus(), null);
            return true;
        }
        if (!"approved".equals(interaction.getStatus())) return false;

        StudentProject project = projectService.getOwnedProject(interaction.getStudentId(), interaction.getProjectId());
        if (project == null) {
            failNetworkRetryProtocol(interaction, "The project for the approved network retry is unavailable");
            return false;
        }
        AgentRunExecutionLeaseService.ExecutionLease executionLease = acquireExecutionLease(approval);
        if (executionLeaseService != null && executionLease == null) {
            return true;
        }
        AgentRunInteractionService.NetworkRetryClaim claim;
        try {
            claim = networkAccessService.claimOfflineRetry(interaction);
        } catch (RuntimeException failure) {
            releaseExecutionLease(executionLease);
            log.error("NETWORK_RETRY_CLAIM_FAILED taskId={} interactionId={}",
                    interaction.getTaskId(), interaction.getInteractionId(), failure);
            return false;
        }
        if (!claim.claimed()) {
            releaseExecutionLease(executionLease);
            String status = claim.interaction() == null ? "" : String.valueOf(claim.interaction().getStatus());
            if ("consumed".equals(status)) {
                resumeAgentLoop(approval, "network_retry_completed", null);
                return true;
            }
            return "executing".equals(status);
        }

        AgentCancellationRegistry.ActiveRun activeRun = null;
        try {
            if (leaseHeartbeatService != null && executionLease != null) {
                leaseHeartbeatService.track(executionLease, approval.getSessionId());
            }
            activeRun = cancellationRegistry.register(
                    approval.getSessionId(), approval.getStudentId(), approval.getProjectId(), approval.getTaskId());
            lifecycleService.appendEvent(approval.getTaskId(), "NETWORK_RETRY_EXECUTION_STARTED",
                    publicPayload(approval, Map.of("interactionId", interaction.getInteractionId(),
                            "requestDigest", retry.requestDigest(), "resumeAgentLoop", false)),
                    lifecycleKey(approval, "network-retry-started"));
            long startedNanos = System.nanoTime();
            ProcessExecutionResult result = executor.executeNetworkRetry(
                    approval, project, activeRun, identity -> recordExecutionProcessBound(approval, identity));
            auditService.recordExecutionOutcome(approval, result, result.durationMs());
            recordVerificationOutcome(approval, result, "network_retry");
            String executionStatus = result.succeeded() ? "completed" : "failed";
            lifecycleService.appendEvent(approval.getTaskId(),
                    result.succeeded() ? "NETWORK_RETRY_EXECUTION_COMPLETED" : "NETWORK_RETRY_EXECUTION_FAILED",
                    publicPayload(approval, Map.of(
                            "interactionId", interaction.getInteractionId(),
                            "executionStatus", executionStatus,
                            "exitCode", result.exitCode() == null ? "" : result.exitCode(),
                            "durationMs", result.durationMs(),
                            "resumeAgentLoop", true)),
                    lifecycleKey(approval, "network-retry-outcome:" + executionStatus));
            if (result.status() == ExecutionStatus.CANCELLED) {
                networkAccessService.completeOfflineRetry(interaction, Map.of(
                        "executionStatus", "cancelled", "durationMs", result.durationMs()));
                completeCancelledExecution(approval, result, approval.getStudentId(), approval.getProjectId(),
                        result.durationMs(), elapsedMs(startedNanos), startedNanos);
                return true;
            }
            closeApprovedToolCall(approval, result.succeeded(), result);
            networkAccessService.completeOfflineRetry(interaction, Map.of(
                    "executionStatus", executionStatus,
                    "exitCode", result.exitCode() == null ? "" : result.exitCode(),
                    "durationMs", result.durationMs()));
            releaseExecutionLease(executionLease);
            executionLease = null;
            resumeAgentLoop(approval, "network_retry_" + executionStatus, result);
            metadataRefreshScheduler.schedule(
                    approval.getStudentId(), approval.getProjectId(), "network_command_retry");
            return true;
        } catch (Exception failure) {
            String message = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            ProcessExecutionResult result = new ProcessExecutionResult(
                    ExecutionStatus.INFRASTRUCTURE_ERROR, null, 0L,
                    "Network-approved command retry failed before a terminal process result: " + message, false);
            try {
                recordVerificationOutcome(approval, result, "network_retry");
                lifecycleService.appendEvent(approval.getTaskId(), "NETWORK_RETRY_EXECUTION_FAILED",
                        publicPayload(approval, Map.of("interactionId", interaction.getInteractionId(),
                                "executionStatus", "infrastructure_error", "resumeAgentLoop", true)),
                        lifecycleKey(approval, "network-retry-outcome:infrastructure_error"));
                closeApprovedToolCall(approval, false, result);
                networkAccessService.completeOfflineRetry(interaction, Map.of(
                        "executionStatus", "infrastructure_error", "error", message));
            } catch (Exception projectionFailure) {
                failure.addSuppressed(projectionFailure);
            }
            releaseExecutionLease(executionLease);
            executionLease = null;
            resumeAgentLoop(approval, "network_retry_infrastructure_error", result);
            log.error("NETWORK_RETRY_EXECUTION_FAILED taskId={} interactionId={}",
                    interaction.getTaskId(), interaction.getInteractionId(), failure);
            return true;
        } finally {
            cancellationRegistry.complete(activeRun);
            releaseExecutionLease(executionLease);
        }
    }

    /** JVM 重启后只恢复已有结果；副作用未知的执行绝不自动重放，而是将 Tool Part 标记为 interrupted。 */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${labex-agent.network-retry-recovery-poll-interval-ms:1000}")
    public void recoverClaimedOfflineNetworkRetries() {
        if (networkAccessService == null || executionLeaseService == null) return;
        LocalDateTime now = LocalDateTime.now();
        for (AgentRunInteraction interaction : networkAccessService.claimedOfflineRetries(100)) {
            NetworkAccessService.OfflineRetryDescriptor retry = networkAccessService.offlineRetryDescriptor(interaction);
            CommandApproval approval = retry == null ? null : offlineNetworkRetryApproval(interaction, retry);
            if (approval == null) continue;
            AgentTask task = taskService.getOwnedTask(
                    interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId());
            if (task == null || executionLeaseService.hasActiveLease(task, now)) continue;
            if (transcriptService.hasPersistedToolResult(approval.getTaskId(), approval.getToolCallId())) {
                networkAccessService.completeOfflineRetry(interaction, Map.of("executionStatus", "recovered"));
                resumeAgentLoop(approval, "network_retry_recovered", null);
                continue;
            }
            LocalDateTime updated = interaction.getUpdateTime();
            if (updated != null && updated.plusNanos(executionLeaseService.leaseDurationMs() * 1_000_000L).isAfter(now)) {
                continue;
            }
            String detail = "status=interrupted\nnetwork_retry=unknown_after_restart\n"
                    + "The command will not be replayed because its side effects are unknown.";
            transcriptService.appendDeferredToolResult(
                    approval.getTaskId(), approval.getToolCallId(), "", detail);
            toolCallJournalService.interruptedExisting(approval.getTaskId(), approval.getToolCallId(), detail);
            networkAccessService.completeOfflineRetry(interaction, Map.of(
                    "executionStatus", "interrupted", "reason", "unknown_after_restart"));
            lifecycleService.appendEvent(approval.getTaskId(), "NETWORK_RETRY_EXECUTION_INTERRUPTED",
                    publicPayload(approval, Map.of("interactionId", interaction.getInteractionId(),
                            "resumeAgentLoop", true)), lifecycleKey(approval, "network-retry-interrupted"));
            resumeAgentLoop(approval, "network_retry_interrupted", null);
        }
    }

    /**
     * 只读判定：该 offline retry 是否关联了合法的命令审批（只有这种才能服务端精确重放）。
     * 无审批关联的 offline retry（如 run_tests 自动批准的 verification 命令失败后创建的重试审批）
     * 应走通用交互恢复，由模型消费已批准的 grant 重新发起命令，而不是在这里触发协议失败。
     */
    public boolean canResumeOfflineNetworkRetry(AgentRunInteraction interaction) {
        if (networkAccessService == null) {
            return false;
        }
        NetworkAccessService.OfflineRetryDescriptor retry = networkAccessService.offlineRetryDescriptor(interaction);
        return retry != null && offlineNetworkRetryApproval(interaction, retry) != null;
    }

    private CommandApproval offlineNetworkRetryApproval(
            AgentRunInteraction interaction, NetworkAccessService.OfflineRetryDescriptor retry) {
        CommandApproval approval = retry.approvalId() == null || retry.approvalId().isBlank()
                ? approvalService.findLatestForTask(
                        interaction.getStudentId(), interaction.getProjectId(), interaction.getTaskId())
                : approvalService.findOwned(
                        interaction.getStudentId(), interaction.getProjectId(), retry.approvalId());
        if (approval == null || !"agent_shell".equals(approval.getSource())
                || !"consumed".equals(approval.getStatus())
                || !Objects.equals(approval.getTaskId(), interaction.getTaskId())
                || !Objects.equals(approval.getToolCallId(), retry.toolCallId())
                || !Objects.equals(approval.getCanonicalCommand(), retry.command())
                || !Objects.equals(networkAccessService.digest(approval.getCanonicalCommand()), retry.requestDigest())) {
            return null;
        }
        return approval;
    }

    private void failNetworkRetryProtocol(AgentRunInteraction interaction, String detail) {
        if (interaction == null || interaction.getTaskId() == null) return;
        lifecycleService.transition(
                interaction.getTaskId(), AgentRunState.FAILED, "NETWORK_RETRY_PROTOCOL_FAILED",
                Map.of("interactionId", String.valueOf(interaction.getInteractionId()),
                        "detail", detail, "resumeAgentLoop", false),
                "Unable to resume approved network command", detail,
                "network-retry-protocol-failed-" + interaction.getInteractionId());
    }

    private ExecutionResult completeCancelledExecution(CommandApproval approval, ProcessExecutionResult result,
                                                       Integer studentId, Integer projectId,
                                                       long processDurationMs, long orchestrationDurationMs,
                                                       long requestStartedNanos) {
        return completeCancelledExecution(approval, result, studentId, projectId, processDurationMs,
                orchestrationDurationMs, requestStartedNanos, WorkspaceChangeEvidence.unavailable());
    }

    private ExecutionResult completeCancelledExecution(CommandApproval approval, ProcessExecutionResult result,
                                                       Integer studentId, Integer projectId,
                                                       long processDurationMs, long orchestrationDurationMs,
                                                       long requestStartedNanos,
                                                       WorkspaceChangeEvidence workspaceEvidence) {
        WorkspaceChangeEvidence evidence = workspaceEvidence == null
                ? WorkspaceChangeEvidence.unavailable() : workspaceEvidence;
        String output = result.output() == null ? "" : CommandRedactor.redact(result.output());
        String detail = "status=interrupted\nexecution_status=cancelled\nexit="
                + (result.exitCode() == null ? "none" : result.exitCode())
                + (output.isBlank() ? "" : "\n" + output)
                + evidence.toolResultDetail();
        Exception projectionFailure = null;
        projectionFailure = runCancellationProjection(approval, projectId, "audit", projectionFailure,
                () -> auditService.recordExecutionInterrupted(approval, "user_cancellation"));
        projectionFailure = runCancellationProjection(approval, projectId, "transcript", projectionFailure,
                () -> transcriptService.appendDeferredToolResult(
                        approval.getTaskId(), approval.getToolCallId(), "", detail));
        projectionFailure = runCancellationProjection(approval, projectId, "tool_part", projectionFailure,
                () -> toolCallJournalService.interruptedExisting(
                        approval.getTaskId(), approval.getToolCallId(), detail));
        projectionFailure = runCancellationProjection(approval, projectId, "event", projectionFailure,
                () -> lifecycleService.appendEvent(approval.getTaskId(), "COMMAND_EXECUTION_CANCELLED",
                        publicPayload(approval, Map.of(
                                "executionStatus", "cancelled",
                                "durationMs", processDurationMs,
                                "resumeAgentLoop", false)),
                        lifecycleKey(approval, "execution-outcome:cancelled")));
        if (evidence.hasChanges()) {
            // 与正常执行一致：进程被取消并不等于其先前写入不存在，必须先持久化目标证据再刷新 UI。
            projectionFailure = runCancellationProjection(approval, projectId, "workspace_changed", projectionFailure,
                    () -> lifecycleService.appendEvent(approval.getTaskId(), "WORKSPACE_CHANGED",
                            workspaceChangedPayload(approval, "cancelled", evidence, false),
                            lifecycleKey(approval, "workspace-changed")));
        }


        boolean finalized = false;
        try {
            finalized = taskService.finalizeCancellation(approval.getTaskId(), "Cancelled",
                    "User cancelled the approved command execution");
        } catch (Exception exception) {
            projectionFailure = mergeCancellationFailure(approval, projectId, "task_finalization",
                    projectionFailure, exception);
        }
        String status = finalized ? "cancelled" : "cancelling";
        if (!finalized) {
            log.error("COMMAND_APPROVAL_CANCELLATION_FINALIZATION_DEFERRED taskId={} projectId={} approvalId={}",
                    approval.getTaskId(), projectId, approval.getApprovalId());
        }
        projectionFailure = runCancellationProjection(approval, projectId, "metadata_refresh", projectionFailure,
                () -> metadataRefreshScheduler.schedule(studentId, projectId, "command_approval_cancelled"));
        if (projectionFailure != null) {
            log.error("COMMAND_APPROVAL_CANCELLATION_PROJECTION_DEGRADED taskId={} projectId={} approvalId={} executionStatus={}",
                    approval.getTaskId(), projectId, approval.getApprovalId(), status, projectionFailure);
        }
        log.info("COMMAND_APPROVAL_PROCESS_FINISHED taskId={} projectId={} approvalId={} executionStatus={} exitCode={} processDurationMs={} orchestrationDurationMs={}",
                approval.getTaskId(), projectId, approval.getApprovalId(), status,
                result.exitCode(), processDurationMs, orchestrationDurationMs);
        log.info("COMMAND_APPROVAL_EXECUTION_HTTP_RETURNED taskId={} projectId={} approvalId={} executionStatus={} resumeAgentLoop=false totalElapsedMs={}",
                approval.getTaskId(), projectId, approval.getApprovalId(), status, elapsedMs(requestStartedNanos));
        return ExecutionResult.available(approval, result, status);
    }

    private Exception runCancellationProjection(CommandApproval approval, Integer projectId, String step,
                                                Exception currentFailure, Runnable action) {
        try {
            action.run();
            return currentFailure;
        } catch (Exception exception) {
            return mergeCancellationFailure(approval, projectId, step, currentFailure, exception);
        }
    }

    private Exception mergeCancellationFailure(CommandApproval approval, Integer projectId, String step,
                                               Exception currentFailure, Exception exception) {
        log.error("COMMAND_APPROVAL_CANCELLATION_STEP_FAILED taskId={} projectId={} approvalId={} step={}",
                approval.getTaskId(), projectId, approval.getApprovalId(), step, exception);
        if (currentFailure == null) {
            return exception;
        }
        currentFailure.addSuppressed(exception);
        return currentFailure;
    }

    private void recordVerificationOutcome(CommandApproval approval, ProcessExecutionResult result, String strategy) {
        if (approval == null || result == null || verificationRecorder == null
                || !"run_tests".equals(approvalToolName(approval.getCanonicalCommand()))) {
            return;
        }
        verificationRecorder.recordProcessResult(
                approval.getTaskId(), approval.getStudentId(), approval.getProjectId(),
                approval.getDisplayCommand(), strategy, result);
    }

    private String approvalToolName(String command) {
        if (command == null) return "run_command";
        String normalized = command.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("mvn") || normalized.startsWith("npm")
                || normalized.startsWith("gradle") || normalized.startsWith("./gradlew")
                || normalized.startsWith("python") || normalized.startsWith("pytest")
                ? "run_tests" : "run_command";
    }

    private void recordExecutionProcessBound(
            CommandApproval approval, com.labex.labexagent.execution.ProcessExecutionIdentity identity) {
        auditService.recordExecutionProcessBound(approval, identity);
        lifecycleService.appendEvent(approval.getTaskId(), "COMMAND_EXECUTION_PROCESS_BOUND",
                publicPayload(approval, Map.of(
                        "processIdentityPersisted", true,
                        "workerRuntime", identity.workerRuntime(),
                        "workerRunId", identity.workerRunId(),
                        "leaseExpiresEpochMs", identity.leaseExpiresEpochMs(),
                        "resumeAgentLoop", false)),
                lifecycleKey(approval, "execution-process-bound"));
    }

    private AgentRunExecutionLeaseService.ExecutionLease acquireExecutionLease(CommandApproval approval) {
        if (executionLeaseService == null) {
            return null;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2L);
        do {
            AgentRunExecutionLeaseService.ExecutionLease lease = executionLeaseService.acquire(approval.getTaskId());
            if (lease != null) {
                return lease;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.nanoTime() < deadline);
        return null;
    }

    private void releaseExecutionLease(AgentRunExecutionLeaseService.ExecutionLease lease) {
        if (lease == null) {
            return;
        }
        if (leaseHeartbeatService != null) {
            leaseHeartbeatService.untrack(lease);
        }
        if (executionLeaseService != null) {
            executionLeaseService.release(lease);
        }
    }

    private boolean consume(CommandApproval approval) {
        return approvalService.consume(new CommandApprovalService.ConsumeRequest(
                approval.getApprovalId(), approval.getStudentId(), approval.getProjectId(), approval.getTaskId(),
                approval.getConversationId(), approval.getSessionId(), approval.getSource(), approval.getInvocationId(),
                approval.getToolCallId(), approval.getCommandDigest(), approval.getCanonicalCommand(),
                approval.getWorkingDirectory(), approval.getShell(), approval.getCommandOptions(),
                approval.getClassification(), approval.getPolicyVersion(), approval.getExpiresTime()));
    }

    private boolean resolveRejected(CommandApproval approval, String status, String decisionIdempotencyKey) {
        if (!isLatestTaskApproval(approval)) {
            log.info("COMMAND_APPROVAL_RESOLUTION_SUPERSEDED taskId={} projectId={} approvalId={} status={}",
                    approval.getTaskId(), approval.getProjectId(), approval.getApprovalId(), status);
            return false;
        }
        lifecycleService.appendEvent(approval.getTaskId(), "COMMAND_APPROVAL_" + status.toUpperCase(),
                publicPayload(approval, Map.of("decision", status, "resumeAgentLoop", true)),
                lifecycleKey(approval, "resolution:" + decisionIdempotencyKey));
        failApprovedToolCall(approval, "expired".equals(status)
                ? "Command approval expired" : "Command approval was rejected");
        if (!isLatestTaskApproval(approval)) {
            log.info("COMMAND_APPROVAL_RESUME_SKIPPED taskId={} projectId={} approvalId={} reason=superseded_after_transition",
                    approval.getTaskId(), approval.getProjectId(), approval.getApprovalId());
            return false;
        }
        return resumeAgentLoop(approval, status, null);
    }

    private boolean shouldRequestNetworkRetry(CommandApproval approval, ProcessExecutionResult result) {
        if (networkAccessService == null || approval == null || result == null
                || networkEnabled(approval.getCommandOptions()) || result.succeeded()) return false;
        String command = approval.getCanonicalCommand();
        return EnvironmentBlockerClassifier.isNetworkRetryCandidate(
                approvalToolName(command), ToolResult.fromProcessExecution(result))
                && !networkAccessService.hasOfflineRetryAttempt(approval.getTaskId(), command);
    }

    private NetworkAccessService.NetworkAccessRequest createNetworkRetry(CommandApproval approval,
                                                                           ProcessExecutionResult result) {
        try {
            String output = result.output() == null ? "" : result.output().replaceAll("\\s+", " ");
            String summary = "\u68c0\u6d4b\u5230\u547d\u4ee4\u5728\u79bb\u7ebf\u7f51\u7edc\u73af\u5883\u4e0b\u5931\u8d25\uff1b\u5141\u8bb8\u540e\u5c06\u4ec5\u91cd\u8bd5\u5f53\u524d\u547d\u4ee4\u4e00\u6b21\u3002\n\u5931\u8d25\u6458\u8981\uff1a"
                    + (output.length() <= 500 ? output : output.substring(0, 500));
            return networkAccessService.beginOfflineCommandRetry(
                    approval.getStudentId(), approval.getProjectId(), approval.getTaskId(),
                    approval.getConversationId(), approval.getSessionId(), approvalToolName(approval.getCanonicalCommand()),
                    approval.getCanonicalCommand(), summary, approval.getToolCallId(), approval.getToolCallId(),
                    approval.getApprovalId(), java.util.List.of());
        } catch (RuntimeException exception) {
            log.warn("Unable to create network retry approval taskId={} approvalId={}: {}",
                    approval.getTaskId(), approval.getApprovalId(), exception.getMessage());
            return null;
        }
    }

    private boolean networkEnabled(String options) {
        return options != null && java.util.Arrays.stream(options.split(";"))
                .anyMatch(part -> "network=true".equalsIgnoreCase(part.trim()));
    }

    private void closeApprovedToolCall(CommandApproval approval, boolean succeeded, ProcessExecutionResult result) {
        closeApprovedToolCall(approval, succeeded, result, WorkspaceChangeEvidence.unavailable());
    }

    private void closeApprovedToolCall(CommandApproval approval, boolean succeeded, ProcessExecutionResult result,
                                       WorkspaceChangeEvidence workspaceEvidence) {
        if (approval == null) return;
        WorkspaceChangeEvidence evidence = workspaceEvidence == null
                ? WorkspaceChangeEvidence.unavailable() : workspaceEvidence;
        WorkspaceOperationIdentity identity = workspaceOperationIdentity(approval, evidence);
        String workdir = identity == null ? "." : identity.workingDirectory();
        ToolResult toolResult = ToolResult.fromProcessExecution(redactedProcessResult(result),
                safeShellName(approval), workdir, null);
        String detail = "status=" + (succeeded ? "completed" : "failed")
                + "\n" + toolResult.getContent()
                + evidence.toolResultDetail();
        toolResult.setContent(detail);
        if (identity != null) {
            toolResult.withWorkspaceIdentity(identity);
        }
        if (evidence.hasChanges()) {
            toolResult.withWorkspaceChangeEvidence(identity, evidence.changeIds());
        }
        if (evidence.hasWorkspaceVerification()) {
            toolResult.withWorkspaceVerification(evidence.workspaceVerification());
        }
        resolveApprovedToolCall(approval, toolResult);
    }

    private String safeShellName(CommandApproval approval) {
        if (approval == null || approval.getShell() == null || approval.getShell().isBlank()) {
            return "direct";
        }
        String shell = approval.getShell().trim().toLowerCase(java.util.Locale.ROOT);
        return shell.matches("[a-z0-9._-]{1,32}") ? shell : "direct";
    }

    /** 控制面复用 shell 输出时继续沿用审批路径既有的敏感信息脱敏边界。 */
    private ProcessExecutionResult redactedProcessResult(ProcessExecutionResult result) {
        if (result == null) return null;
        return new ProcessExecutionResult(result.status(), result.exitCode(), result.durationMs(),
                CommandRedactor.redact(result.output()), result.truncated(), result.outputPath(), result.outputChars());
    }

    private GitSnapshotService.Snapshot captureWorkspaceSnapshot(StudentProject project, CommandApproval approval,
                                                                  String labelPrefix) {
        if (snapshotService == null || project == null || approval == null) {
            return null;
        }
        try {
            return snapshotService.capture(project, labelPrefix + approval.getApprovalId());
        } catch (RuntimeException exception) {
            log.warn("COMMAND_APPROVAL_SNAPSHOT_CAPTURE_FAILED taskId={} approvalId={} phase={} errorType={}",
                    approval.getTaskId(), approval.getApprovalId(), labelPrefix.trim(),
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private WorkspaceChangeEvidence recordWorkspaceChangeEvidence(CommandApproval approval, StudentProject project,
                                                                    GitSnapshotService.Snapshot beforeSnapshot) {
        if (snapshotService == null || diffService == null || approval == null || project == null
                || beforeSnapshot == null || !beforeSnapshot.available()) {
            return WorkspaceChangeEvidence.unavailable();
        }
        try {
            GitSnapshotService.Snapshot afterSnapshot = captureWorkspaceSnapshot(project, approval,
                    "after approved command ");
            if (afterSnapshot == null || !afterSnapshot.available()) {
                return WorkspaceChangeEvidence.unavailable();
            }
            List<PendingChange> changes = diffService.recordSnapshotDiffWithoutTaskProjection(
                    approval.getStudentId(), project, approval.getConversationId(), approval.getTaskId(),
                    "command_approval", beforeSnapshot, afterSnapshot);
            return WorkspaceChangeEvidence.from(changes, verifyWorkspacePostconditions(project, changes));
        } catch (RuntimeException exception) {
            log.warn("COMMAND_APPROVAL_SNAPSHOT_EVIDENCE_FAILED taskId={} approvalId={} errorType={}",
                    approval.getTaskId(), approval.getApprovalId(), exception.getClass().getSimpleName());
            return WorkspaceChangeEvidence.unavailable();
        }
    }

    /**
     * Snapshot 证明“命令期间观察到变更”，这里再对每个安全相对 target 做一次真实文件系统后置检查。
     * 它不试图猜测任意 shell 命令的意图，只验证已落入 change-set 的实际 target，避免把 rm -f 的
     * 空成功或快照后的竞争误投影成已验证删除。
     */
    private Map<String, Object> verifyWorkspacePostconditions(StudentProject project, List<PendingChange> changes) {
        List<PendingChange> safeChanges = changes == null ? List.of() : changes.stream()
                .filter(Objects::nonNull)
                .filter(change -> change.getRelativePath() != null && !change.getRelativePath().isBlank())
                .toList();
        if (safeChanges.isEmpty()) {
            return Map.of("state", "no_change", "targets", List.of());
        }
        try {
            SecureWorkspacePath workspace = ProjectWorkspace.paths(project);
            List<Map<String, Object>> targets = new ArrayList<>();
            boolean verified = true;
            for (PendingChange change : safeChanges) {
                String expectedState = "delete".equalsIgnoreCase(change.getChangeType())
                        ? "absent" : "regular_file";
                Path target = workspace.resolveForCreate(change.getRelativePath());
                String observedState;
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    observedState = "absent";
                } else if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    observedState = "regular_file";
                } else {
                    observedState = "not_regular_file";
                }
                boolean targetVerified = expectedState.equals(observedState);
                verified = verified && targetVerified;
                Map<String, Object> targetEvidence = new LinkedHashMap<>();
                targetEvidence.put("path", change.getRelativePath().replace('\\', '/'));
                targetEvidence.put("expectedState", expectedState);
                targetEvidence.put("observedState", observedState);
                targets.add(Map.copyOf(targetEvidence));
            }
            return Map.of("state", verified ? "verified" : "mismatch", "targets", List.copyOf(targets));
        } catch (RuntimeException exception) {
            // 工作区不可检查时不虚构成功，只持久化可解释的安全状态。
            return Map.of("state", "unavailable", "targets", List.of());
        }
    }

    private void failApprovedToolCall(CommandApproval approval, String detail) {
        if (approval != null) {
            resolveApprovedToolCall(approval, detail == null ? "status=interrupted" : detail);
        }
    }

    private void resolveApprovedToolCall(CommandApproval approval, String detail) {
        if (approval == null) return;
        String safeDetail = detail == null ? "" : detail;
        transcriptService.appendDeferredToolResult(approval.getTaskId(), approval.getToolCallId(), "", safeDetail);
        toolCallJournalService.completedExisting(approval.getTaskId(), approval.getToolCallId(), safeDetail);
    }

    private void resolveApprovedToolCall(CommandApproval approval, ToolResult result) {
        if (approval == null) return;
        String detail = result == null || result.getContent() == null ? "" : result.getContent();
        transcriptService.appendDeferredToolResult(approval.getTaskId(), approval.getToolCallId(), "", detail);
        // 协议层的 tool call 已经结束；真实命令成功/失败仍由 ToolResult 内容与 metadata 供模型和 UI 判断。
        toolCallJournalService.completedExisting(approval.getTaskId(), approval.getToolCallId(),
                result == null ? ToolResult.ok(detail) : result);
    }

    /**
     * transcript 已经持久化后，scheduler 必须取得与状态迁移同事务生成的 lease 才能恢复 Agent。
     * 如果旧 worker 的 lease 仍然有效，保留 waiting_approval 并等待 scheduler 接管。
     */
    private boolean resumeAgentLoop(CommandApproval approval, String resolutionStatus, ProcessExecutionResult result) {
        CommandApprovalResumeScheduler.ResumeResult resumeResult = commandResumeScheduler.resumeIfWaiting(approval);
        if (resumeResult == CommandApprovalResumeScheduler.ResumeResult.RESUMED) {
            log.info("COMMAND_APPROVAL_AGENT_RESUME_ENQUEUED taskId={} projectId={} approvalId={} resolutionStatus={}",
                    approval.getTaskId(), approval.getProjectId(), approval.getApprovalId(), resolutionStatus);
            return true;
        }
        if (resumeResult == CommandApprovalResumeScheduler.ResumeResult.DEFERRED_ACTIVE_LEASE) {
            lifecycleService.appendEvent(approval.getTaskId(), "COMMAND_APPROVAL_RESUME_DEFERRED",
                    publicPayload(approval, Map.of("resolutionStatus", resolutionStatus,
                            "reason", "previous_execution_lease_active", "resumeAgentLoop", true)),
                    lifecycleKey(approval, "resume-deferred"));
            log.info("COMMAND_APPROVAL_AGENT_RESUME_DEFERRED taskId={} projectId={} approvalId={} resolutionStatus={}",
                    approval.getTaskId(), approval.getProjectId(), approval.getApprovalId(), resolutionStatus);
            return true;
        }
        log.info("COMMAND_APPROVAL_AGENT_RESUME_SKIPPED taskId={} projectId={} approvalId={} resolutionStatus={} reason=task_not_waiting_or_superseded",
                approval.getTaskId(), approval.getProjectId(), approval.getApprovalId(), resolutionStatus);
        return false;
    }

    private boolean isLatestTaskApproval(CommandApproval approval) {
        if (approval == null || approval.getTaskId() == null) {
            return false;
        }
        CommandApproval latest = approvalService.findLatestForTask(
                approval.getStudentId(), approval.getProjectId(), approval.getTaskId());
        return latest != null && Objects.equals(latest.getApprovalId(), approval.getApprovalId());
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private CommandApproval ownedAgentApproval(Integer studentId, Integer projectId, String approvalId) {
        CommandApproval approval = approvalService.findOwned(studentId, projectId, approvalId);
        return approval != null && "agent_shell".equals(approval.getSource()) ? approval : null;
    }

    private String lifecycleKey(CommandApproval approval, String suffix) {
        return "command-lifecycle:v1:" + approval.getApprovalId() + ":" + suffix;
    }

    private Map<String, Object> publicPayload(CommandApproval approval, Map<String, Object> additional) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approval.getApprovalId());
        payload.put("taskId", approval.getTaskId());
        payload.put("sessionId", approval.getSessionId());
        payload.put("toolCallId", approval.getToolCallId());
        payload.put("displayCommand", CommandRedactor.redact(approval.getDisplayCommand()));
        payload.put("resumeAgentLoop", true);
        payload.putAll(additional);
        return payload;
    }

    private Map<String, Object> workspaceChangedPayload(CommandApproval approval, String executionStatus,
                                                         WorkspaceChangeEvidence evidence, boolean resumeAgentLoop) {
        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put("projectId", approval.getProjectId());
        additional.put("source", "command_approval");
        additional.put("workspaceChangeId", approval.getApprovalId() + ":workspace-changed");
        additional.put("workingDirectory", approval.getWorkingDirectory());
        additional.put("executionStatus", executionStatus);
        additional.put("evidenceStatus", evidence.status());
        additional.put("changedFileCount", evidence.changedFileCount());
        additional.put("changedPaths", evidence.changedPaths());
        WorkspaceOperationIdentity identity = workspaceOperationIdentity(approval, evidence);
        additional.put("workspaceIdentityStatus", identity == null ? "unavailable" : "captured");
        if (identity != null) {
            additional.put("workspaceIdentity", identity.toPayload());
        }
        if (evidence.hasChanges()) {
            additional.put("workspaceMutation", Map.of(
                    "state", "applied",
                    "changeIds", evidence.changeIds()));
        }
        if (evidence.hasWorkspaceVerification()) {
            additional.put("workspaceVerification", evidence.workspaceVerification());
        }
        additional.put("resumeAgentLoop", resumeAgentLoop);
        return publicPayload(approval, additional);
    }

    /** 证据投影失败不得掩盖真实命令结果；同时要明确告诉 reducer target identity 不可用。 */
    private WorkspaceOperationIdentity workspaceOperationIdentity(CommandApproval approval,
                                                                   WorkspaceChangeEvidence evidence) {
        if (approval == null) {
            return null;
        }
        try {
            StudentProject project = projectService.getOwnedProject(approval.getStudentId(), approval.getProjectId());
            if (project == null) {
                return null;
            }
            AgentTask task = taskService.getOwnedTask(approval.getStudentId(), approval.getProjectId(),
                    approval.getTaskId());
            return WorkspaceOperationIdentity.forCommandApproval(project, approval, task, evidence.changedPaths());
        } catch (RuntimeException invalidIdentity) {
            log.warn("COMMAND_APPROVAL_WORKSPACE_IDENTITY_UNAVAILABLE taskId={} approvalId={} errorType={}",
                    approval.getTaskId(), approval.getApprovalId(), invalidIdentity.getClass().getSimpleName());
            return null;
        }
    }

    private record WorkspaceChangeEvidence(String status, int changedFileCount, List<String> changedPaths,
                                           List<PendingChange> changes,
                                           Map<String, Object> workspaceVerification) {
        private static WorkspaceChangeEvidence unavailable() {
            return new WorkspaceChangeEvidence("unavailable", 0, List.of(), List.of(),
                    Map.of("state", "unavailable", "targets", List.of()));
        }

        private static WorkspaceChangeEvidence from(List<PendingChange> changes,
                                                    Map<String, Object> workspaceVerification) {
            List<PendingChange> safeChanges = changes == null ? List.of() : changes.stream()
                    .filter(Objects::nonNull)
                    .toList();
            if (safeChanges.isEmpty()) {
                return new WorkspaceChangeEvidence("no_change", 0, List.of(), List.of(),
                        workspaceVerification == null || workspaceVerification.isEmpty()
                                ? Map.of("state", "no_change", "targets", List.of())
                                : Map.copyOf(workspaceVerification));
            }
            List<String> paths = safeChanges.stream()
                    .map(PendingChange::getRelativePath)
                    .filter(path -> path != null && !path.isBlank())
                    .distinct()
                    .sorted()
                    .limit(40)
                    .toList();
            return new WorkspaceChangeEvidence("captured", safeChanges.size(), List.copyOf(paths),
                    List.copyOf(safeChanges), workspaceVerification == null || workspaceVerification.isEmpty()
                    ? Map.of("state", "unavailable", "targets", List.of())
                    : Map.copyOf(workspaceVerification));
        }

        private boolean hasChanges() {
            return changedFileCount > 0;
        }

        private List<String> changeIds() {
            return changes.stream()
                    .map(PendingChange::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();
        }

        private boolean hasWorkspaceVerification() {
            return !"unavailable".equals(String.valueOf(workspaceVerification.get("state")));
        }

        private String toolResultDetail() {
            String detail = "\nworkspace_evidence=" + status + "\nchanged_file_count=" + changedFileCount
                    + "\nworkspace_verification=" + workspaceVerification.getOrDefault("state", "unavailable");
            return changedPaths.isEmpty() ? detail : detail + "\nchanged_paths=" + String.join(",", changedPaths);
        }
    }

    public record DecisionResult(boolean available, CommandApproval approval, boolean resumeAgentLoop) {
        static DecisionResult unavailable() { return new DecisionResult(false, null, false); }
        static DecisionResult available(CommandApproval approval, boolean resumeAgentLoop) {
            return new DecisionResult(true, approval, resumeAgentLoop);
        }
    }

    public record ExecutionResult(boolean available, CommandApproval approval,
                                  ProcessExecutionResult result, String status) {
        static ExecutionResult unavailable() { return new ExecutionResult(false, null, null, ""); }
        static ExecutionResult available(CommandApproval approval, ProcessExecutionResult result, String status) {
            return new ExecutionResult(true, approval, result, status);
        }
    }
}
