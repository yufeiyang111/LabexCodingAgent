package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AgentRunExecutionLeaseServiceTest {

    @Test
    void claimsAndRenewsAnExecutionLeaseWithAFencingEpoch() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTask task = task();
        when(mapper.selectById(71L)).thenReturn(task);
        when(mapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 10, 0);

        AgentRunExecutionLeaseService.ExecutionLease lease = service.acquire(71L, now);

        assertNotNull(lease);
        assertEquals(1L, lease.epoch());
        assertEquals("instance-a", lease.owner());
        assertEquals(71L, lease.taskId());
        org.junit.jupiter.api.Assertions.assertTrue(service.renew(lease, now.plusSeconds(5)));
    }

    @Test
    void refusesASecondWorkerEvenWhenTheUnexpiredLeaseHasTheSameInstanceOwner() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTask task = task();
        task.setExecutionOwner("instance-a");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.of(2026, 7, 23, 10, 1));
        when(mapper.selectById(71L)).thenReturn(task);
        when(mapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);

        assertNull(service.acquire(71L, LocalDateTime.of(2026, 7, 23, 10, 0)));
    }

    @Test
    void refusesToRunWhenAnotherUnexpiredOwnerWinsTheCompareAndSet() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTask task = task();
        task.setExecutionOwner("instance-b");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.of(2026, 7, 23, 10, 1));
        when(mapper.selectById(71L)).thenReturn(task);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);

        assertNull(service.acquire(71L, LocalDateTime.of(2026, 7, 23, 10, 0)));
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStatus("running");
        task.setExecutionEpoch(0L);
        return task;
    }
}
