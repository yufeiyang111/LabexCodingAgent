package com.labex.labexagent.commandsecurity;

import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunContinuationRequestFactory;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.CommandFailureGuard;
import com.labex.labexagent.run.EnvironmentBlockerClassifier;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.run.AgentRunState;
import com.labex.service.StudentProjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
    private final AgentTaskService taskService;
    private final AgentLoopEngine agentLoopEngine;
    private final AgentProjectMetadataRefreshScheduler metadataRefreshScheduler;
    private CommandFailureGuard commandFailureGuard = new CommandFailureGuard(1, 2);
    private AgentToolCallJournalService toolCallJournalService;

    public CommandApprovalOrchestrator(CommandApprovalService approvalService, CommandAuditService auditService,
                                       AgentApprovedCommandExecutor executor, StudentProjectService projectService,
                                       AgentRunLifecycleService lifecycleService, AgentTaskService taskService,
                                       @Lazy AgentLoopEngine agentLoopEngine,
                                       AgentProjectMetadataRefreshScheduler metadataRefreshScheduler) {
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.executor = executor;
        this.projectService = projectService;
        this.lifecycleService = lifecycleService;
        this.taskService = taskService;
        this.agentLoopEngine = agentLoopEngine;
        this.metadataRefreshScheduler = metadataRefreshScheduler;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCommandFailureGuard(CommandFailureGuard commandFailureGuard) {
        if (commandFailureGuard != null) this.commandFailureGuard = commandFailureGuard;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setToolCallJournalService(AgentToolCallJournalService toolCallJournalService) {
        this.toolCallJournalService = toolCallJournalService;
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
        try {
            lifecycleService.transition(approval.getTaskId(), AgentRunState.RUNNING,
                    "COMMAND_EXECUTION_STARTED", publicPayload(approval, Map.of("resumeAgentLoop", false)),
                    "Executing approved command", "Executing the stored one-time command",
                    lifecycleKey(approval, "execution-started"));
            log.info("COMMAND_APPROVAL_PROCESS_STARTED taskId={} projectId={} approvalId={} workingDirectory={}",
                    approval.getTaskId(), projectId, approval.getApprovalId(), approval.getWorkingDirectory());
            long processStartedNanos = System.nanoTime();
            ProcessExecutionResult result = executor.execute(approval, project);
            long orchestrationDurationMs = elapsedMs(processStartedNanos);
            long processDurationMs = result.durationMs();
            auditService.recordExecutionOutcome(approval, result, processDurationMs);
            boolean succeeded = result.succeeded();
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
                lifecycleService.transition(approval.getTaskId(), AgentRunState.RECOVERING,
                        "COMMAND_EXECUTION_RESUME_QUEUED",
                        publicPayload(approval, Map.of("executionStatus", executionStatus, "resumeAgentLoop", true)),
                        "Resuming Agent after approved command",
                        "The one-time command finished and the Agent continuation is queued.",
                        lifecycleKey(approval, "execution-resume:" + executionStatus));
                resumeAgentLoop(approval, executionStatus, result);
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
        }
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
        lifecycleService.transition(approval.getTaskId(), AgentRunState.RECOVERING,
                "COMMAND_APPROVAL_RESOLVED", publicPayload(approval, Map.of("decision", status, "resumeAgentLoop", true)),
                "Resolving command approval", "The one-time command approval was resolved.",
                lifecycleKey(approval, "resolve:" + decisionIdempotencyKey));
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
        resumeAgentLoop(approval, status, null);
        return true;
    }

    private void closeApprovedToolCall(CommandApproval approval, boolean succeeded, ProcessExecutionResult result) {
        if (toolCallJournalService == null || approval == null) return;
        String output = result == null ? "" : CommandRedactor.redact(result.output());
        String detail = "status=" + (succeeded ? "completed" : "failed")
                + "\nexit=" + (result == null || result.exitCode() == null ? "none" : result.exitCode())
                + (output == null || output.isBlank() ? "" : "\n" + output);
        if (succeeded) {
            toolCallJournalService.completedExisting(approval.getTaskId(), approval.getToolCallId(), detail);
        } else {
            toolCallJournalService.failedExisting(approval.getTaskId(), approval.getToolCallId(), detail);
        }
    }

    private void failApprovedToolCall(CommandApproval approval, String detail) {
        if (toolCallJournalService != null && approval != null) {
            toolCallJournalService.failedExisting(approval.getTaskId(), approval.getToolCallId(), detail);
        }
    }

    private void resumeAgentLoop(CommandApproval approval, String resolutionStatus, ProcessExecutionResult result) {
        AgentTask task = taskService.getOwnedTask(approval.getStudentId(), approval.getProjectId(), approval.getTaskId());
        if (task == null) {
            throw new IllegalStateException("Agent task is unavailable for command continuation");
        }
        String output = result == null ? "" : CommandRedactor.redact(result.output());
        if (output.length() > 4_000) output = output.substring(0, 4_000) + "...";
        String continuation = """
                The one-time command approval has been resolved and the command must not be replayed.
                Resolution status: %s
                Command exit code: %s
                Redacted command output: %s
                Reassess the workspace, use the recorded command outcome, and continue the existing plan.
                """.formatted(resolutionStatus,
                result == null || result.exitCode() == null ? "" : result.exitCode(), output);
        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, continuation);
        log.info("COMMAND_APPROVAL_AGENT_RESUME_REQUEST taskId={} projectId={} approvalId={} resolutionStatus={}",
                approval.getTaskId(), approval.getProjectId(), approval.getApprovalId(), resolutionStatus);
        agentLoopEngine.resume(approval.getStudentId(), approval.getProjectId(), request, approval.getTaskId(), true);
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
