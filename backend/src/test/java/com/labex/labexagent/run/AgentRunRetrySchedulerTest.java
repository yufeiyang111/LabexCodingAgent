package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunRetrySchedulerTest {

    @Test
    void resumesOnlyAClaimedDueRetry() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
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
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(lifecycle.beginScheduledRetry(eq(71L), eq(1), any(), eq("model-retry-start-71-1"))).thenReturn(true);
        AgentRunExecutionLeaseService executionLeases = mock(AgentRunExecutionLeaseService.class);
        AgentRunRetryScheduler scheduler = new AgentRunRetryScheduler(taskMapper, lifecycle, executionLeases, engine);

        int resumed = scheduler.resumeDueRetries(LocalDateTime.of(2026, 7, 23, 10, 0, 1));

        assertEquals(1, resumed);
        verify(engine).resume(eq(7), eq(12), any(), eq(71L), eq(true));
    }

    @Test
    void leavesTheRetryPendingUntilThePreviousExecutionLeaseIsReleased() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService executionLeases = mock(AgentRunExecutionLeaseService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setStatus("retrying");
        task.setRetryAttempts(1);
        task.setNextRetryAt(LocalDateTime.of(2026, 7, 23, 10, 0));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(executionLeases.hasActiveLease(eq(task), any())).thenReturn(true);
        AgentRunRetryScheduler scheduler = new AgentRunRetryScheduler(taskMapper, lifecycle, executionLeases, engine);

        int resumed = scheduler.resumeDueRetries(LocalDateTime.of(2026, 7, 23, 10, 0, 1));

        assertEquals(0, resumed);
        verify(lifecycle, never()).beginScheduledRetry(any(), any(Integer.class), any(), any());
        verify(engine, never()).resume(any(), any(), any(), any(), any(Boolean.class));
    }
}
