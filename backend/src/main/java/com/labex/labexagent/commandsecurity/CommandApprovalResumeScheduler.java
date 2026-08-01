package com.labex.labexagent.commandsecurity;

import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.run.AgentRunContinuationRequestFactory;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 只负责已经完成的一次性命令审批的 continuation dispatch，命令本身不会在这里重放。
 * command approval 的 consumed 状态 和 durable tool result 才是命令执行事实。
 */
@Service
public class CommandApprovalResumeScheduler {
    private static final Logger log = LoggerFactory.getLogger(CommandApprovalResumeScheduler.class);
    private static final int BATCH_SIZE = 100;
    private static final String CURRENT_STEP = "Resuming after approved command";
    private static final String SUMMARY = "The approved command result is persisted and ready for the Agent continuation.";
    private static final String CONTINUATION_TEMPLATE = """
            Command approval decision: %s
            Resolution status: %s
            The one-time command approval has already been resolved. Its durable tool result contains the command outcome.
            Reassess the workspace and continue the existing task without replaying the command.
            """;

    private final AgentTaskService taskService;
    private final AgentRunExecutionLeaseService executionLeaseService;
    private final AgentLoopEngine agentLoopEngine;
    private final CommandApprovalService approvalService;
    private final AgentRunTranscriptService transcriptService;

    public CommandApprovalResumeScheduler(AgentTaskService taskService,
                                          AgentRunExecutionLeaseService executionLeaseService,
                                          @Lazy AgentLoopEngine agentLoopEngine) {
        this(taskService, executionLeaseService, agentLoopEngine, null, null);
    }

    public CommandApprovalResumeScheduler(AgentTaskService taskService,
                                          AgentRunExecutionLeaseService executionLeaseService,
                                          @Lazy AgentLoopEngine agentLoopEngine,
                                          AgentRunTranscriptService transcriptService) {
        this(taskService, executionLeaseService, agentLoopEngine, null, transcriptService);
    }

    @Autowired
    public CommandApprovalResumeScheduler(AgentTaskService taskService,
                                          AgentRunExecutionLeaseService executionLeaseService,
                                          @Lazy AgentLoopEngine agentLoopEngine,
                                          CommandApprovalService approvalService,
                                          AgentRunTranscriptService transcriptService) {
        this.taskService = taskService;
        this.executionLeaseService = executionLeaseService;
        this.agentLoopEngine = agentLoopEngine;
        this.approvalService = approvalService;
        this.transcriptService = transcriptService;
    }

    /** 尝试立即恢复；若上一个 JVM 的 lease 仍有效，则保留 durable waiting state 交给轮询接管。 */
    public ResumeResult resumeIfWaiting(CommandApproval approval) {
        if (!resumable(approval)) {
            return ResumeResult.UNAVAILABLE;
        }
        AgentTask task = taskService.getOwnedTask(approval.getStudentId(), approval.getProjectId(), approval.getTaskId());
        if (!waitingApproval(task)) {
            return ResumeResult.UNAVAILABLE;
        }
        if (!durableToolResultReady(approval)) {
            log.debug("COMMAND_APPROVAL_RESUME_DEFERRED taskId={} approvalId={} reason=tool_result_not_persisted",
                    approval.getTaskId(), approval.getApprovalId());
            return ResumeResult.DEFERRED_TOOL_RESULT;
        }
        if (executionLeaseService.hasActiveLease(task, LocalDateTime.now())) {
            return ResumeResult.DEFERRED_ACTIVE_LEASE;
        }
        log.debug("COMMAND_APPROVAL_RESUME_GATE_PASSED taskId={} approvalId={} toolCallId={}",
                approval.getTaskId(), approval.getApprovalId(), approval.getToolCallId());
        AgentRunLifecycleService.DispatchClaim claim = taskService.claimCommandApprovalResume(
                task.getTaskId(), approval.getApprovalId(), CURRENT_STEP, SUMMARY);
        if (claim == null) {
            return ResumeResult.UNAVAILABLE;
        }
        try {
            AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, continuationFor(approval));
            agentLoopEngine.resume(task.getStudentId(), task.getProjectId(), request, task.getTaskId(), true, claim.lease());
            return ResumeResult.RESUMED;
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "The executor rejected the durable command continuation."
                    : "The executor rejected the durable command continuation: " + failure.getMessage();
            log.error("Unable to enqueue approved command continuation taskId={} approvalId={}",
                    task.getTaskId(), approval.getApprovalId(), failure);
            taskService.updateTask(task.getTaskId(), "failed", "Command continuation dispatch failed", reason,
                    "command-approval-resume-dispatch-failed-" + task.getTaskId() + "-" + approval.getApprovalId());
            return ResumeResult.FAILED;
        }
    }

    private String continuationFor(CommandApproval approval) {
        String resolutionStatus = switch (Objects.requireNonNullElse(approval.getStatus(), "unknown")) {
            case "consumed" -> "approved";
            case "rejected" -> "rejected";
            case "expired" -> "expired";
            default -> approval.getStatus();
        };
        return CONTINUATION_TEMPLATE.formatted(resolutionStatus, resolutionStatus);
    }

    @Scheduled(fixedDelayString = "${labex-agent.command-approval-resume-poll-interval-ms:1000}")
    public void resumeDeferred() {
        if (approvalService == null) {
            return;
        }
        List<CommandApproval> approvals = approvalService.findResolvedAgentApprovalsAwaitingResume(BATCH_SIZE);
        for (CommandApproval approval : approvals) {
            if (!isLatest(approval)) {
                continue;
            }
            try {
                ResumeResult result = resumeIfWaiting(approval);
                if (result == ResumeResult.RESUMED) {
                    log.info("COMMAND_APPROVAL_DEFERRED_RESUME_ENQUEUED taskId={} approvalId={}",
                            approval.getTaskId(), approval.getApprovalId());
                }
            } catch (RuntimeException failure) {
                log.error("COMMAND_APPROVAL_DEFERRED_RESUME_FAILED taskId={} approvalId={}",
                        approval.getTaskId(), approval.getApprovalId(), failure);
            }
        }
    }

    private boolean isLatest(CommandApproval approval) {
        CommandApproval latest = approvalService.findLatestForTask(
                approval.getStudentId(), approval.getProjectId(), approval.getTaskId());
        return latest != null && Objects.equals(latest.getApprovalId(), approval.getApprovalId());
    }

    private boolean resumable(CommandApproval approval) {
        if (approval == null || approval.getApprovalId() == null || approval.getTaskId() == null
                || approval.getStudentId() == null || approval.getProjectId() == null
                || approval.getToolCallId() == null || approval.getToolCallId().isBlank()
                || !"agent_shell".equals(approval.getSource())) {
            return false;
        }
        return "consumed".equals(approval.getStatus())
                || "rejected".equals(approval.getStatus())
                || "expired".equals(approval.getStatus());
    }

    /** consumed 只表示一次性能力不可重放；Provider 续跑还必须等待最终 tool result 落入持久化 transcript。 */
    private boolean durableToolResultReady(CommandApproval approval) {
        return transcriptService != null
                && transcriptService.hasPersistedToolResult(approval.getTaskId(), approval.getToolCallId());
    }

    private boolean waitingApproval(AgentTask task) {
        return task != null && task.getTaskId() != null && task.getStudentId() != null
                && task.getProjectId() != null && task.getConversationId() != null
                && "waiting_approval".equals(task.getStatus());
    }

    public enum ResumeResult {
        RESUMED,
        DEFERRED_TOOL_RESULT,
        DEFERRED_ACTIVE_LEASE,
        UNAVAILABLE,
        FAILED
    }
}
