package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentLoopEngine;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AgentRunTakeoverSchedulerTest {

    @Test
    void claimsAnExpiredLeaseAndQueuesExactlyOneResume() {
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = task();
        when(leases.instanceId()).thenReturn("instance-new");
        when(leases.leaseDurationMs()).thenReturn(30_000L);
        when(lifecycle.claimRecovery(71L, AgentRunState.RUNNING, "instance-new", 30_000L))
                .thenReturn(claim(), null);
        AgentRunTakeoverScheduler scheduler = new AgentRunTakeoverScheduler(leases, lifecycle, engine);

        assertTrue(scheduler.takeover(task));
        assertFalse(scheduler.takeover(task));

        verify(engine, times(1)).resume(eq(7), eq(12), any(), eq(71L), eq(true), any(AgentRunExecutionLeaseService.ExecutionLease.class));
    }

    @Test
    void failsAClaimedTakeoverOnlyAfterDurableContextValidationFails() {
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = task();
        task.setConversationId(" ");
        when(leases.instanceId()).thenReturn("instance-new");
        when(leases.leaseDurationMs()).thenReturn(30_000L);
        when(lifecycle.claimRecovery(71L, AgentRunState.RUNNING, "instance-new", 30_000L)).thenReturn(claim());
        AgentRunTakeoverScheduler scheduler = new AgentRunTakeoverScheduler(leases, lifecycle, engine);

        assertFalse(scheduler.takeover(task));

        InOrder order = inOrder(lifecycle, engine);
        order.verify(lifecycle).claimRecovery(71L, AgentRunState.RUNNING, "instance-new", 30_000L);
        order.verify(lifecycle).transition(eq(71L), eq(AgentRunState.FAILED), eq("RUN_RECOVERY_TAKEOVER_FAILED"),
                any(), any(), any(), any());
        verify(engine, never()).resume(any(), any(), any(), any(), anyBoolean(), any(AgentRunExecutionLeaseService.ExecutionLease.class));
        verify(leases).release(any(AgentRunExecutionLeaseService.ExecutionLease.class));
    }

    @Test
    void failsAClaimedTakeoverAfterResumeEnqueueFails() {
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = task();
        when(leases.instanceId()).thenReturn("instance-new");
        when(leases.leaseDurationMs()).thenReturn(30_000L);
        when(lifecycle.claimRecovery(71L, AgentRunState.RUNNING, "instance-new", 30_000L)).thenReturn(claim());
        org.mockito.Mockito.doThrow(new IllegalStateException("queue rejected"))
                .when(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true), any(AgentRunExecutionLeaseService.ExecutionLease.class));
        AgentRunTakeoverScheduler scheduler = new AgentRunTakeoverScheduler(leases, lifecycle, engine);

        assertFalse(scheduler.takeover(task));

        InOrder order = inOrder(lifecycle, engine);
        order.verify(lifecycle).claimRecovery(71L, AgentRunState.RUNNING, "instance-new", 30_000L);
        order.verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true), any(AgentRunExecutionLeaseService.ExecutionLease.class));
        order.verify(lifecycle).transition(eq(71L), eq(AgentRunState.FAILED), eq("RUN_RECOVERY_TAKEOVER_FAILED"),
                any(), any(), any(), any());
        verify(leases).release(any(AgentRunExecutionLeaseService.ExecutionLease.class));
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setSessionId("session-1");
        task.setMode("agent");
        task.setStatus("running");
        return task;
    }

    private AgentRunLifecycleService.RecoveryClaim claim() {
        return new AgentRunLifecycleService.RecoveryClaim(
                "instance-new", 5L, LocalDateTime.now().plusSeconds(30));
    }
}
