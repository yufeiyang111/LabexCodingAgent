package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunRecoveryServiceTest {

    @Test
    void closesRunningCompactionWhenTheLeaseBelongsToANewerExecutionEpoch() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunExecutionLeaseService leaseService = mock(AgentRunExecutionLeaseService.class);
        AgentCompactionService compactionService = mock(AgentCompactionService.class);
        AgentCompactionRecord record = runningCompaction(41L, 71L, 3L);
        AgentTask task = task(71L, AgentRunState.RUNNING);
        task.setExecutionEpoch(4L);
        when(compactionService.runningRecordsAfter(0L, 200)).thenReturn(List.of(record));
        when(taskMapper.selectById(71L)).thenReturn(task);
        when(leaseService.hasActiveLease(eq(task), any())).thenReturn(true);
        AgentRunRecoveryService service = new AgentRunRecoveryService(taskMapper,
                mock(AgentRunLifecycleService.class), leaseService, mock(AgentRunTakeoverScheduler.class),
                mock(AgentRunPartService.class), mock(AgentRunMessageService.class), compactionService);

        assertEquals(1, service.recoverInterruptedCompactions());

        verify(compactionService).fail(eq(record), anyString());
    }

    @Test
    void keepsRunningCompactionForTheSameActiveExecutionLease() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunExecutionLeaseService leaseService = mock(AgentRunExecutionLeaseService.class);
        AgentCompactionService compactionService = mock(AgentCompactionService.class);
        AgentCompactionRecord record = runningCompaction(41L, 71L, 3L);
        AgentTask task = task(71L, AgentRunState.RUNNING);
        task.setExecutionEpoch(3L);
        when(compactionService.runningRecordsAfter(0L, 200)).thenReturn(List.of(record));
        when(taskMapper.selectById(71L)).thenReturn(task);
        when(leaseService.hasActiveLease(eq(task), any())).thenReturn(true);
        AgentRunRecoveryService service = new AgentRunRecoveryService(taskMapper,
                mock(AgentRunLifecycleService.class), leaseService, mock(AgentRunTakeoverScheduler.class),
                mock(AgentRunPartService.class), mock(AgentRunMessageService.class), compactionService);

        assertEquals(0, service.recoverInterruptedCompactions());

        verify(compactionService, never()).fail(any(), anyString());
    }

    @Test
    void failsQueuedRunsThatWereInterruptedByAServiceRestart() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTask task = task(71L, AgentRunState.QUEUED);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(lifecycle.recordRecoveryAttemptIfCurrent(anyLong(), any())).thenReturn(1);
        AgentRunRecoveryService service = newRecoveryService(taskMapper, lifecycle, mock(AgentRunTakeoverScheduler.class));

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        verify(lifecycle).recordRecoveryAttemptIfCurrent(71L, AgentRunState.QUEUED);
        verify(lifecycle).transition(
                eq(71L),
                eq(AgentRunState.FAILED),
                eq("RUN_RECOVERY_FAILED"),
                any(),
                any(),
                any(),
                eq("recovery-71-failed"));
    }

    @Test
    void safelyClaimedTakeoverDoesNotFallThroughToRecoveryFailure() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunTakeoverScheduler takeoverScheduler = mock(AgentRunTakeoverScheduler.class);
        AgentTask task = task(71L, AgentRunState.RUNNING);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(takeoverScheduler.takeover(task)).thenReturn(true);
        AgentRunRecoveryService service = newRecoveryService(taskMapper, lifecycle, takeoverScheduler);

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        verify(takeoverScheduler).takeover(task);
        verify(taskMapper, never()).updateById(task);
        verify(lifecycle, never()).transition(
                eq(71L), eq(AgentRunState.FAILED), any(), any(), any(), any(), any());
    }

    @Test
    void retainsWaitingInteractionsSoTheirPersistedReplyCanResumeTheRun() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTask task = task(72L, AgentRunState.WAITING_APPROVAL);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(lifecycle.recordRecoveryAttemptIfCurrent(anyLong(), any())).thenReturn(1);
        AgentRunRecoveryService service = newRecoveryService(taskMapper, lifecycle, mock(AgentRunTakeoverScheduler.class));

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        verify(lifecycle).appendEventIfCurrent(
                eq(72L),
                eq(AgentRunState.WAITING_APPROVAL),
                eq("RUN_RECOVERY_WAITING"),
                any(),
                eq("recovery-72-waiting"));
        verify(lifecycle, never()).transition(
                eq(72L),
                eq(AgentRunState.CANCELLING),
                any(), any(), any(), any(), any());
    }

    @Test
    void marksConsumedApprovalWithoutDurableOutcomeAsUncertainWithoutReplay() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTask task = task(72L, AgentRunState.WAITING_APPROVAL);
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-72");
        approval.setTaskId(72L);
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setToolCallId("tool-72");
        approval.setSource("agent_shell");
        approval.setStatus("consumed");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(lifecycle.recordRecoveryAttemptIfCurrent(72L, AgentRunState.WAITING_APPROVAL)).thenReturn(1);
        when(approvals.findLatestForTask(7, 12, 72L)).thenReturn(approval);
        when(transcript.hasPersistedToolResult(72L, "tool-72")).thenReturn(false);
        AgentRunRecoveryService service = new AgentRunRecoveryService(taskMapper, lifecycle,
                mock(AgentRunExecutionLeaseService.class), mock(AgentRunTakeoverScheduler.class),
                mock(AgentRunPartService.class), mock(AgentRunMessageService.class),
                mock(AgentCompactionService.class), approvals, transcript);

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        verify(lifecycle).appendEventIfCurrent(
                eq(72L), eq(AgentRunState.WAITING_APPROVAL),
                eq("COMMAND_EXECUTION_RECOVERY_UNCERTAIN"), any(),
                eq("recovery-72-command-execution-uncertain-approval-72"));
        verify(lifecycle).appendEventIfCurrent(
                eq(72L), eq(AgentRunState.WAITING_APPROVAL),
                eq("RUN_RECOVERY_WAITING"), any(), eq("recovery-72-waiting"));
        verify(lifecycle, never()).transition(eq(72L), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retainsPersistedRetriesForTheRetrySchedulerAfterAServiceRestart() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTask task = task(73L, AgentRunState.RETRYING);
        task.setRetryAttempts(1);
        task.setNextRetryAt(java.time.LocalDateTime.of(2026, 7, 23, 10, 1));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(lifecycle.recordRecoveryAttemptIfCurrent(anyLong(), any())).thenReturn(1);
        AgentRunRecoveryService service = newRecoveryService(taskMapper, lifecycle, mock(AgentRunTakeoverScheduler.class));

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        verify(lifecycle).appendEventIfCurrent(
                eq(73L), eq(AgentRunState.RETRYING), eq("RUN_RECOVERY_RETRY_PENDING"),
                any(), eq("recovery-73-retry-pending"));
        verify(lifecycle, never()).transition(eq(73L), eq(AgentRunState.FAILED), any(), any(), any(), any(), any());
    }

    @Test
    void ignoresStaleRecoverySnapshotAfterTaskReachedTerminalState() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTask task = task(74L, AgentRunState.WAITING_USER);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(lifecycle.recordRecoveryAttemptIfCurrent(74L, AgentRunState.WAITING_USER)).thenReturn(null);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunMessageService messageService = mock(AgentRunMessageService.class);
        AgentRunRecoveryService service = new AgentRunRecoveryService(taskMapper, lifecycle,
                mock(AgentRunExecutionLeaseService.class), mock(AgentRunTakeoverScheduler.class),
                partService, messageService, mock(AgentCompactionService.class));

        assertEquals(1, service.recoverInterruptedRuns());
        verify(lifecycle).recordRecoveryAttemptIfCurrent(74L, AgentRunState.WAITING_USER);
        verify(lifecycle, never()).appendEventIfCurrent(anyLong(), any(), anyString(), any(), anyString());
        verify(partService, never()).interruptOpenParts(anyLong(), anyString());
        verify(messageService, never()).markOpenMessages(anyLong(), anyString(), anyString());
        verify(taskMapper, never()).updateById(any(AgentTask.class));
    }

    private AgentTask task(Long taskId, AgentRunState state) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setStatus(state.persistedStatus());
        task.setRecoveryAttempts(0);
        return task;
    }

    private AgentRunRecoveryService newRecoveryService(AgentTaskMapper taskMapper,
                                                       AgentRunLifecycleService lifecycle,
                                                       AgentRunTakeoverScheduler takeoverScheduler) {
        return new AgentRunRecoveryService(taskMapper, lifecycle,
                mock(AgentRunExecutionLeaseService.class), takeoverScheduler,
                mock(AgentRunPartService.class), mock(AgentRunMessageService.class),
                mock(AgentCompactionService.class));
    }

    private AgentCompactionRecord runningCompaction(Long compactionId, Long taskId, Long executionEpoch) {
        AgentCompactionRecord record = new AgentCompactionRecord();
        record.setCompactionId(compactionId);
        record.setTaskId(taskId);
        record.setExecutionEpoch(executionEpoch);
        record.setCompactionEpoch(2L);
        record.setStatus("running");
        return record;
    }
}
