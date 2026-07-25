package com.labex.labexagent.commandsecurity;

import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.run.AgentRunState;
import com.labex.service.StudentProjectService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Coordinates one-time command approval decisions and execution, then resumes the durable Agent
 * loop with the redacted command outcome. It owns lifecycle transitions; controllers only authenticate and serialize its results.
 */
@Service
public class CommandApprovalOrchestrator {
    private final CommandApprovalService approvalService;
    private final CommandAuditService auditService;
    private final AgentApprovedCommandExecutor executor;
    private final StudentProjectService projectService;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentTaskService taskService;
    private final AgentLoopEngine agentLoopEngine;

    public CommandApprovalOrchestrator(CommandApprovalService approvalService, CommandAuditService auditService,
                                       AgentApprovedCommandExecutor executor, StudentProjectService projectService,
                                       AgentRunLifecycleService lifecycleService, AgentTaskService taskService,
                                       @Lazy AgentLoopEngine agentLoopEngine) {
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.executor = executor;
        this.projectService = projectService;
        this.lifecycleService = lifecycleService;
        this.taskService = taskService;
        this.agentLoopEngine = agentLoopEngine;
    }

    public DecisionResult decide(Integer studentId, Integer projectId, String approvalId,
                                 boolean approve, String decisionIdempotencyKey) {
        CommandApproval approval = ownedAgentApproval(studentId, projectId, approvalId);
        if (approval == null) {
            return DecisionResult.unavailable();
        }
        CommandApproval decided = approvalService.decide(studentId, projectId, approvalId, approve,
                decisionIdempotencyKey);
        if (!"agent_shell".equals(decided.getSource())) {
            return DecisionResult.unavailable();
        }
        String status = decided.getStatus();
        if ("rejected".equals(status) || "expired".equals(status)) {
            resolveRejected(decided, status, decisionIdempotencyKey);
        } else {
            lifecycleService.appendEvent(decided.getTaskId(), "COMMAND_APPROVAL_DECIDED",
                    publicPayload(decided, Map.of("decision", status)),
                    lifecycleKey(decided, "decision:" + decisionIdempotencyKey));
        }
        return DecisionResult.available(decided);
    }

    public ExecutionResult execute(Integer studentId, Integer projectId, String approvalId) {
        StudentProject project = projectService.getOwnedProject(studentId, projectId);
        CommandApproval approval = ownedAgentApproval(studentId, projectId, approvalId);
        if (project == null || approval == null || !consume(approval)) {
            return ExecutionResult.unavailable();
        }
        approval.setStatus("consumed");
        try {
            lifecycleService.transition(approval.getTaskId(), AgentRunState.RUNNING,
                    "COMMAND_EXECUTION_STARTED", publicPayload(approval, Map.of("resumeAgentLoop", false)),
                    "Executing approved command", "Executing the stored one-time command",
                    lifecycleKey(approval, "execution-started"));
            long startedAt = System.nanoTime();
            ProcessExecutionResult result = executor.execute(approval, project);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            auditService.recordExecutionOutcome(approval, result, durationMs);
            boolean succeeded = result.succeeded();
            lifecycleService.appendEvent(approval.getTaskId(),
                    succeeded ? "COMMAND_EXECUTION_COMPLETED" : "COMMAND_EXECUTION_FAILED",
                    publicPayload(approval, Map.of(
                            "executionStatus", succeeded ? "completed" : "failed",
                            "exitCode", result.exitCode() == null ? "" : result.exitCode(),
                            "resumeAgentLoop", true)),
                    lifecycleKey(approval, "execution-outcome:" + (succeeded ? "completed" : "failed")));
            projectService.refreshProjectMetadata(studentId, projectId);
            resumeAgentLoop(approval, succeeded ? "completed" : "failed", result);
            return ExecutionResult.available(approval, result, "resuming");
        } catch (Exception exception) {
            try {
                auditService.recordExecutionInterrupted(approval, "orchestration_failure");
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

    private boolean consume(CommandApproval approval) {
        return approvalService.consume(new CommandApprovalService.ConsumeRequest(
                approval.getApprovalId(), approval.getStudentId(), approval.getProjectId(), approval.getTaskId(),
                approval.getConversationId(), approval.getSessionId(), approval.getSource(), approval.getInvocationId(),
                approval.getToolCallId(), approval.getCommandDigest(), approval.getCanonicalCommand(),
                approval.getWorkingDirectory(), approval.getShell(), approval.getCommandOptions(),
                approval.getClassification(), approval.getPolicyVersion(), approval.getExpiresTime()));
    }

    private void resolveRejected(CommandApproval approval, String status, String decisionIdempotencyKey) {
        lifecycleService.transition(approval.getTaskId(), AgentRunState.RUNNING,
                "COMMAND_APPROVAL_RESOLVED", publicPayload(approval, Map.of("decision", status, "resumeAgentLoop", true)),
                "Resolving command approval", "The one-time command approval was resolved.",
                lifecycleKey(approval, "resolve:" + decisionIdempotencyKey));
        lifecycleService.appendEvent(approval.getTaskId(), "COMMAND_APPROVAL_" + status.toUpperCase(),
                publicPayload(approval, Map.of("decision", status, "resumeAgentLoop", true)),
                lifecycleKey(approval, "resolution:" + decisionIdempotencyKey));
        resumeAgentLoop(approval, status, null);
    }

    private void resumeAgentLoop(CommandApproval approval, String resolutionStatus, ProcessExecutionResult result) {
        AgentTask task = taskService.getOwnedTask(approval.getStudentId(), approval.getProjectId(), approval.getTaskId());
        if (task == null) {
            throw new IllegalStateException("Agent task is unavailable for command continuation");
        }
        AgentStreamRequest request = new AgentStreamRequest();
        request.setSessionId(approval.getSessionId());
        request.setConversationId(approval.getConversationId());
        request.setMode(task.getMode());
        request.setResumeTaskId(approval.getTaskId());
        String output = result == null ? "" : CommandRedactor.redact(result.output());
        if (output.length() > 4_000) output = output.substring(0, 4_000) + "...";
        request.setMessage("""
                Continue the existing task from its durable conversation history.
                The one-time command approval has been resolved and the command must not be replayed.
                Resolution status: %s
                Command exit code: %s
                Redacted command output: %s
                Reassess the workspace, use the recorded command outcome, and continue the existing plan.
                """.formatted(resolutionStatus,
                result == null || result.exitCode() == null ? "" : result.exitCode(), output));
        agentLoopEngine.resume(approval.getStudentId(), approval.getProjectId(), request, approval.getTaskId(), true);
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

    public record DecisionResult(boolean available, CommandApproval approval) {
        static DecisionResult unavailable() { return new DecisionResult(false, null); }
        static DecisionResult available(CommandApproval approval) { return new DecisionResult(true, approval); }
    }

    public record ExecutionResult(boolean available, CommandApproval approval,
                                  ProcessExecutionResult result, String status) {
        static ExecutionResult unavailable() { return new ExecutionResult(false, null, null, ""); }
        static ExecutionResult available(CommandApproval approval, ProcessExecutionResult result, String status) {
            return new ExecutionResult(true, approval, result, status);
        }
    }
}
