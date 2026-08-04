package com.labex.labexagent.commandsecurity;

import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.CommandFailureGuard;
import com.labex.labexagent.run.EnvironmentBlockerClassifier;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.run.AgentRunState;
import com.labex.labexagent.network.NetworkAccessService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.service.StudentProjectService;
import java.util.LinkedHashMap;
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
        if (project == null || approval == null || !isLatestTaskApproval(approval) || !consume(approval)) {
            log.warn("COMMAND_APPROVAL_EXECUTION_UNAVAILABLE projectId={} approvalId={} reason=superseded_missing_or_consumed",
                    projectId, approvalId);
            return ExecutionResult.unavailable();
        }
        log.info("COMMAND_APPROVAL_EXECUTION_REQUEST_ACCEPTED taskId={} projectId={} approvalId={} workingDirectory={} requestElapsedMs={}",
                approval.getTaskId(), projectId, approval.getApprovalId(), approval.getWorkingDirectory(), elapsedMs(requestStartedNanos));
        approval.setStatus("consumed");
        AgentCancellationRegistry.ActiveRun activeRun = null;
        try {
            activeRun = cancellationRegistry.register(approval.getSessionId(), studentId, projectId, approval.getTaskId());
            lifecycleService.appendEvent(approval.getTaskId(), "COMMAND_EXECUTION_STARTED",
                    publicPayload(approval, Map.of("resumeAgentLoop", false)),
                    lifecycleKey(approval, "execution-started"));
            log.info("COMMAND_APPROVAL_PROCESS_STARTED taskId={} projectId={} approvalId={} workingDirectory={}",
                    approval.getTaskId(), projectId, approval.getApprovalId(), approval.getWorkingDirectory());
            long processStartedNanos = System.nanoTime();
            ProcessExecutionResult result = executor.execute(approval, project, activeRun);
            long orchestrationDurationMs = elapsedMs(processStartedNanos);
            long processDurationMs = result.durationMs();
            if (result.status() == ExecutionStatus.CANCELLED) {
                return completeCancelledExecution(approval, result, studentId, projectId,
                        processDurationMs, orchestrationDurationMs, requestStartedNanos);
            }
            auditService.recordExecutionOutcome(approval, result, processDurationMs);
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
            closeApprovedToolCall(approval, succeeded, result);
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
        }
    }

    private ExecutionResult completeCancelledExecution(CommandApproval approval, ProcessExecutionResult result,
                                                       Integer studentId, Integer projectId,
                                                       long processDurationMs, long orchestrationDurationMs,
                                                       long requestStartedNanos) {
        String output = result.output() == null ? "" : CommandRedactor.redact(result.output());
        String detail = "status=interrupted\nexecution_status=cancelled\nexit="
                + (result.exitCode() == null ? "none" : result.exitCode())
                + (output.isBlank() ? "" : "\n" + output);
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

    private String approvalToolName(String command) {
        if (command == null) return "run_command";
        String normalized = command.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("mvn") || normalized.startsWith("npm")
                || normalized.startsWith("gradle") || normalized.startsWith("./gradlew")
                || normalized.startsWith("python") || normalized.startsWith("pytest")
                ? "run_tests" : "run_command";
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
        return looksLikeNetworkFailure(result.output())
                && !networkAccessService.hasOfflineRetryAttempt(approval.getTaskId(), command);
    }

    private NetworkAccessService.NetworkAccessRequest createNetworkRetry(CommandApproval approval,
                                                                           ProcessExecutionResult result) {
        try {
            String output = result.output() == null ? "" : result.output().replaceAll("\\s+", " ");
            String summary = "\u68c0\u6d4b\u5230\u547d\u4ee4\u5728\u79bb\u7ebf\u7f51\u7edc\u73af\u5883\u4e0b\u5931\u8d25\uff1b\u5141\u8bb8\u540e\u5c06\u4ec5\u91cd\u8bd5\u5f53\u524d\u547d\u4ee4\u4e00\u6b21\u3002\n\u5931\u8d25\u6458\u8981\uff1a"
                    + (output.length() <= 500 ? output : output.substring(0, 500));
            return networkAccessService.begin(approval.getStudentId(), approval.getProjectId(), approval.getTaskId(),
                    approval.getConversationId(), approval.getSessionId(), approvalToolName(approval.getCanonicalCommand()),
                    approval.getCanonicalCommand(), summary, "offline_failure_retry", true, approval.getToolCallId(),
                    java.util.List.of());
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

    private boolean looksLikeNetworkFailure(String output) {
        String lower = output == null ? "" : output.toLowerCase(java.util.Locale.ROOT);
        String[] markers = {"could not resolve", "temporary failure in name resolution", "name resolution",
                "unknown host", "no such host", "getaddrinfo", "network is unreachable",
                "connection timed out", "connect timed out", "failed to connect", "connection reset",
                "unable to access", "failed to download", "could not download", "download failed",
                "proxy connect", "tls handshake timeout", "network is disabled", "internet is disabled"};
        for (String marker : markers) if (lower.contains(marker)) return true;
        return false;
    }

    private void closeApprovedToolCall(CommandApproval approval, boolean succeeded, ProcessExecutionResult result) {
        if (approval == null) return;
        String output = result == null ? "" : CommandRedactor.redact(result.output());
        String detail = "status=" + (succeeded ? "completed" : "failed")
                + "\nexit=" + (result == null || result.exitCode() == null ? "none" : result.exitCode())
                + (output == null || output.isBlank() ? "" : "\n" + output);
        resolveApprovedToolCall(approval, detail);
    }

    private void failApprovedToolCall(CommandApproval approval, String detail) {
        if (approval != null) {
            resolveApprovedToolCall(approval, detail == null ? "status=interrupted" : detail);
        }
    }

    private void resolveApprovedToolCall(CommandApproval approval, String detail) {
        transcriptService.appendDeferredToolResult(approval.getTaskId(), approval.getToolCallId(), "", detail);
        // The protocol terminal state is completed even when the command outcome is failed.
        // The redacted result content retains the command outcome for the model and UI.
        toolCallJournalService.completedExisting(approval.getTaskId(), approval.getToolCallId(), detail);
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
