package com.labex.labexagent.commandsecurity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.AgentRunState;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.service.StudentProjectService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CommandApprovalOrchestratorTest {

    @Test
    void rejectionQueuesTheSameRunForRecoveryBeforeResuming() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setMode("build");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        CommandApproval approval = approval("pending");
        CommandApproval rejected = approval("rejected");
        rejected.setDecisionIdempotencyKey("decision-71");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.decide(7, 12, "approval-71", false, "decision-71")).thenReturn(rejected);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(rejected);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit,
                mock(AgentApprovedCommandExecutor.class), mock(StudentProjectService.class), lifecycle, tasks, engine,
                mock(AgentProjectMetadataRefreshScheduler.class), toolCalls, transcript);

        CommandApprovalOrchestrator.DecisionResult result = orchestrator.decide(
                7, 12, "approval-71", false, "decision-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isTrue();
        verify(lifecycle).transition(eq(71L), eq(AgentRunState.RECOVERING), eq("COMMAND_APPROVAL_RESOLVED"),
                any(), any(), any(), any());
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_APPROVAL_REJECTED"), any(), any());
        verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true));
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                eq("Command approval was rejected"));
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"),
                eq("Command approval was rejected"));
    }

    @Test
    void approvedCommandExecutionResumesTheExistingAgentLoopInsteadOfCompletingTheTask() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CommandApproval approval = approval("approved");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setMode("build");
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(executor.execute(approval, project)).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 12L, "tests passed", false));
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, tasks, engine, metadataRefresh, toolCalls, transcript);

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("resuming");
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_COMPLETED"), any(), any());
        verify(lifecycle).transition(eq(71L), eq(AgentRunState.RECOVERING),
                eq("COMMAND_EXECUTION_RESUME_QUEUED"), any(), any(), any(), any());
        verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true));
        verify(metadataRefresh).schedule(eq(7), eq(12), eq("command_approval"));
        verify(projects, never()).refreshProjectMetadata(eq(7), eq(12));
        verify(lifecycle, never()).transition(eq(71L), eq(AgentRunState.COMPLETED), any(), any(), any(), any(), any());
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("tests passed"));
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"),
                org.mockito.ArgumentMatchers.contains("tests passed"));
    }

    @Test
    void doesNotExecuteAnApprovalThatHasBeenSupersededByANewerTaskApproval() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        CommandApproval oldApproval = approval("approved");
        CommandApproval currentApproval = approval("pending");
        currentApproval.setApprovalId("approval-72");
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(oldApproval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(currentApproval);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals,
                mock(CommandAuditService.class), executor, projects, lifecycle, mock(AgentTaskService.class), engine,
                mock(AgentProjectMetadataRefreshScheduler.class), mock(AgentToolCallJournalService.class),
                mock(AgentRunTranscriptService.class));

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isFalse();
        verify(approvals).findLatestForTask(7, 12, 71L);
        verify(approvals, never()).consume(any());
        verify(executor, never()).execute(any(), any());
        verify(lifecycle, never()).transition(any(), any(), any(), any(), any(), any(), any());
        verify(engine, never()).resume(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void doesNotResumeTheTaskWhenAnExpiredApprovalHasBeenSuperseded() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CommandApproval oldApproval = approval("pending");
        CommandApproval expiredApproval = approval("expired");
        expiredApproval.setDecisionIdempotencyKey("decision-71");
        CommandApproval currentApproval = approval("pending");
        currentApproval.setApprovalId("approval-72");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(oldApproval);
        when(approvals.decide(7, 12, "approval-71", false, "decision-71")).thenReturn(expiredApproval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(oldApproval, currentApproval);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals,
                mock(CommandAuditService.class), mock(AgentApprovedCommandExecutor.class), mock(StudentProjectService.class),
                lifecycle, mock(AgentTaskService.class), engine, mock(AgentProjectMetadataRefreshScheduler.class),
                mock(AgentToolCallJournalService.class), mock(AgentRunTranscriptService.class));

        CommandApprovalOrchestrator.DecisionResult result = orchestrator.decide(7, 12, "approval-71", false, "decision-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.resumeAgentLoop()).isFalse();
        verify(approvals, org.mockito.Mockito.times(2)).findLatestForTask(7, 12, 71L);
        verify(lifecycle, never()).transition(any(), any(), any(), any(), any(), any(), any());
        verify(engine, never()).resume(any(), any(), any(), any(), anyBoolean());
    }

    private CommandApproval approval(String status) {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setTaskId(71L);
        approval.setConversationId("conversation-71");
        approval.setSessionId("session-71");
        approval.setSource("agent_shell");
        approval.setInvocationId("invoke-71");
        approval.setToolCallId("tool-71");
        approval.setCommandDigest("digest-71");
        approval.setDisplayCommand("npm test --token=<redacted>");
        approval.setClassification("REQUIRE_APPROVAL");
        approval.setPolicyVersion("policy-v1");
        approval.setStatus(status);
        approval.setExpiresTime(LocalDateTime.now().plusMinutes(10));
        return approval;
    }
}
