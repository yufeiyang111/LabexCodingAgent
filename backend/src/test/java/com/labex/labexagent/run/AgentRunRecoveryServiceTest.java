package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunRecoveryServiceTest {

    @Test
    void failsQueuedRunsThatWereInterruptedByAServiceRestart() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTask task = task(71L, AgentRunState.QUEUED);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.updateById(task)).thenReturn(1);
        AgentRunRecoveryService service = newRecoveryService(taskMapper, lifecycle, mock(AgentRunTakeoverScheduler.class));

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        assertEquals(1, task.getRecoveryAttempts());
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
        when(taskMapper.updateById(task)).thenReturn(1);
        AgentRunRecoveryService service = newRecoveryService(taskMapper, lifecycle, mock(AgentRunTakeoverScheduler.class));

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        verify(lifecycle).appendEvent(
                eq(72L),
                eq("RUN_RECOVERY_WAITING"),
                any(),
                eq("recovery-72-waiting"));
        verify(lifecycle, never()).transition(
                eq(72L),
                eq(AgentRunState.CANCELLING),
                any(), any(), any(), any(), any());
    }

    @Test
    void retainsPersistedRetriesForTheRetrySchedulerAfterAServiceRestart() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTask task = task(73L, AgentRunState.RETRYING);
        task.setRetryAttempts(1);
        task.setNextRetryAt(java.time.LocalDateTime.of(2026, 7, 23, 10, 1));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.updateById(task)).thenReturn(1);
        AgentRunRecoveryService service = newRecoveryService(taskMapper, lifecycle, mock(AgentRunTakeoverScheduler.class));

        int recovered = service.recoverInterruptedRuns();

        assertEquals(1, recovered);
        verify(lifecycle).appendEvent(
                eq(73L), eq("RUN_RECOVERY_RETRY_PENDING"), any(), eq("recovery-73-retry-pending"));
        verify(lifecycle, never()).transition(eq(73L), eq(AgentRunState.FAILED), any(), any(), any(), any(), any());
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
                mock(AgentRunPartService.class), mock(AgentRunMessageService.class));
    }
}
