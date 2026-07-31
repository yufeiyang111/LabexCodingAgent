package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunRetryDispatchClaimTest {

    @Test
    void enqueuesOnlyTheNewDispatchAndHandsItsLeaseToTheEngine() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setSessionId("session-1");
        task.setMode("agent");
        task.setStatus("retrying");
        task.setRetryAttempts(1);
        task.setNextRetryAt(LocalDateTime.of(2026, 7, 23, 10, 0));
        when(leases.instanceId()).thenReturn("instance-a");
        when(leases.leaseDurationMs()).thenReturn(30_000L);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 3L, LocalDateTime.of(2026, 7, 23, 10, 1));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(lifecycle.claimScheduledRetry(eq(71L), eq(1), any(), eq("model-retry-start-71-1"), eq("instance-a"), eq(30_000L)))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease));
        AgentRunRetryScheduler scheduler = new AgentRunRetryScheduler(taskMapper, lifecycle, leases, engine);

        int resumed = scheduler.resumeDueRetries(LocalDateTime.of(2026, 7, 23, 10, 0, 1));

        assertEquals(1, resumed);
        verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true), eq(lease));
    }
}
