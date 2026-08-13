package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import java.time.LocalDateTime;
import com.labex.labexagent.dto.AgentStreamRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommandApprovalResumeSchedulerTest {

    @Test
    void defersConsumedApprovalUntilTheDurableToolResultExists() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTask task = waitingTask();
        CommandApproval approval = consumedApproval();
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(transcript.hasPersistedToolResult(71L, "tool-71")).thenReturn(false);
        CommandApprovalResumeScheduler scheduler = new CommandApprovalResumeScheduler(
                tasks, leases, engine, transcript);

        CommandApprovalResumeScheduler.ResumeResult result = scheduler.resumeIfWaiting(approval);

        assertThat(result).isEqualTo(CommandApprovalResumeScheduler.ResumeResult.DEFERRED_TOOL_RESULT);
        verify(leases, never()).hasActiveLease(any(), any());
        verify(tasks, never()).claimCommandApprovalResume(any(), any(), any(), any());
        verify(engine, never()).resume(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void defersResolvedCommandContinuationUntilForeignLeaseExpires() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = waitingTask();
        CommandApproval approval = consumedApproval();
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(leases.hasActiveLease(eq(task), any())).thenReturn(true);

        AgentRunTranscriptService transcript = readyTranscript();
        CommandApprovalResumeScheduler scheduler = new CommandApprovalResumeScheduler(tasks, leases, engine, transcript);

        CommandApprovalResumeScheduler.ResumeResult result = scheduler.resumeIfWaiting(approval);

        assertThat(result).isEqualTo(CommandApprovalResumeScheduler.ResumeResult.DEFERRED_ACTIVE_LEASE);
        verify(tasks, never()).claimCommandApprovalResume(any(), any(), any(), any());
        verify(engine, never()).resume(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void resumesSameTaskWithDurablyClaimedLeaseInsteadOfAcquiringAgainInAgentLoop() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = waitingTask();
        CommandApproval approval = consumedApproval();
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-new", 4L, LocalDateTime.now().plusSeconds(30));
        AgentRunLifecycleService.DispatchClaim claim = new AgentRunLifecycleService.DispatchClaim(lease);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(leases.hasActiveLease(eq(task), any())).thenReturn(false);
        when(tasks.claimCommandApprovalResume(eq(71L), eq("approval-71"), any(), any())).thenReturn(claim);

        AgentRunTranscriptService transcript = readyTranscript();
        CommandApprovalResumeScheduler scheduler = new CommandApprovalResumeScheduler(tasks, leases, engine, transcript);

        CommandApprovalResumeScheduler.ResumeResult result = scheduler.resumeIfWaiting(approval);

        assertThat(result).isEqualTo(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        verify(tasks).claimCommandApprovalResume(eq(71L), eq("approval-71"),
                eq("Resuming after approved command"), eq("The approved command result is persisted and ready for the Agent continuation."));
        ArgumentCaptor<AgentStreamRequest> requestCaptor = ArgumentCaptor.forClass(AgentStreamRequest.class);
        verify(engine).resume(eq(7), eq(12), requestCaptor.capture(), eq(71L), eq(true), eq(lease));
        assertThat(requestCaptor.getValue().getMessage()).isEqualTo("continue original approval task");
        assertThat(requestCaptor.getValue().getMessage())
                .doesNotContain("Original user objective", "Durable continuation context", "Command approval decision");
        assertThat(requestCaptor.getValue().getResumeNote())
                .contains("Command approval decision: approved", "Resolution status: approved");
    }

    @Test
    void marksClaimedContinuationFailedWhenExecutorRejectsTheDispatch() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = waitingTask();
        CommandApproval approval = consumedApproval();
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-new", 4L, LocalDateTime.now().plusSeconds(30));
        AgentRunLifecycleService.DispatchClaim claim = new AgentRunLifecycleService.DispatchClaim(lease);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(leases.hasActiveLease(eq(task), any())).thenReturn(false);
        when(tasks.claimCommandApprovalResume(eq(71L), eq("approval-71"), any(), any())).thenReturn(claim);
        org.mockito.Mockito.doThrow(new IllegalStateException("executor rejected"))
                .when(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true), eq(lease));
        AgentRunTranscriptService transcript = readyTranscript();
        CommandApprovalResumeScheduler scheduler = new CommandApprovalResumeScheduler(tasks, leases, engine, transcript);

        CommandApprovalResumeScheduler.ResumeResult result = scheduler.resumeIfWaiting(approval);

        assertThat(result).isEqualTo(CommandApprovalResumeScheduler.ResumeResult.FAILED);
        verify(tasks).updateTask(eq(71L), eq("failed"), eq("Command continuation dispatch failed"),
                org.mockito.ArgumentMatchers.contains("executor rejected"),
                eq("command-approval-resume-dispatch-failed-71-approval-71"));
    }

    @Test
    void scheduledRetryClaimsContinuationAfterRestartLeaseExpiresWithoutReplayingCommand() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentTask task = waitingTask();
        CommandApproval approval = consumedApproval();
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-new", 5L, LocalDateTime.now().plusSeconds(30));
        AgentRunLifecycleService.DispatchClaim claim = new AgentRunLifecycleService.DispatchClaim(lease);
        when(approvals.findResolvedAgentApprovalsAwaitingResume(100)).thenReturn(java.util.List.of(approval));
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(leases.hasActiveLease(eq(task), any())).thenReturn(true, false);
        when(tasks.claimCommandApprovalResume(eq(71L), eq("approval-71"), any(), any())).thenReturn(claim);
        AgentRunTranscriptService transcript = readyTranscript();
        CommandApprovalResumeScheduler scheduler = new CommandApprovalResumeScheduler(tasks, leases, engine, approvals, transcript);

        scheduler.resumeDeferred();
        verify(engine, never()).resume(any(), any(), any(), any(), anyBoolean(), any());

        scheduler.resumeDeferred();

        verify(tasks).claimCommandApprovalResume(eq(71L), eq("approval-71"), any(), any());
        verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true), eq(lease));
    }

    private AgentRunTranscriptService readyTranscript() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        when(transcript.hasPersistedToolResult(71L, "tool-71")).thenReturn(true);
        return transcript;
    }

    private AgentTask waitingTask() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-71");
        task.setSessionId("session-71");
        task.setMode("build");
        task.setStatus("waiting_approval");
        task.setRequestPayload("{\"message\":\"continue original approval task\",\"displayMessage\":\"continue original approval task\"}");
        return task;
    }

    private CommandApproval consumedApproval() {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setTaskId(71L);
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setConversationId("conversation-71");
        approval.setSessionId("session-71");
        approval.setSource("agent_shell");
        approval.setToolCallId("tool-71");
        approval.setStatus("consumed");
        return approval;
    }
}
