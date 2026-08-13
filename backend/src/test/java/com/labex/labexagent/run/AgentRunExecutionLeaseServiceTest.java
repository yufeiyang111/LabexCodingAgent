package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void activeFencePassesForMatchingOwnerEpochAndUnexpiredLease() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        service.requireActiveFence(fence, LocalDateTime.of(2026, 7, 23, 10, 0));

        verify(mapper).selectCount(any());
    }

    @Test
    void staleOwnerFenceFailsWithTypedReason() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTask task = task();
        task.setExecutionOwner("instance-b");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.of(2026, 7, 23, 10, 1));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(71L)).thenReturn(task);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);

        AgentRunExecutionLeaseService.StaleExecutionFenceException ex = assertThrows(
                AgentRunExecutionLeaseService.StaleExecutionFenceException.class,
                () -> service.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L),
                        LocalDateTime.of(2026, 7, 23, 10, 0)));
        assertEquals(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_OWNER, ex.reason());
    }

    @Test
    void staleEpochFenceFailsWithTypedReason() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTask task = task();
        task.setExecutionOwner("instance-a");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.of(2026, 7, 23, 10, 1));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(71L)).thenReturn(task);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);

        AgentRunExecutionLeaseService.StaleExecutionFenceException ex = assertThrows(
                AgentRunExecutionLeaseService.StaleExecutionFenceException.class,
                () -> service.requireActiveFence(new ExecutionFence(71L, "instance-a", 3L),
                        LocalDateTime.of(2026, 7, 23, 10, 0)));
        assertEquals(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_EPOCH, ex.reason());
    }

    @Test
    void expiredLeaseFenceFailsWithTypedReason() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        AgentTask task = task();
        task.setExecutionOwner("instance-a");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.of(2026, 7, 23, 9, 59));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(71L)).thenReturn(task);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);

        AgentRunExecutionLeaseService.StaleExecutionFenceException ex = assertThrows(
                AgentRunExecutionLeaseService.StaleExecutionFenceException.class,
                () -> service.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L),
                        LocalDateTime.of(2026, 7, 23, 10, 0)));
        assertEquals(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.EXPIRED_LEASE, ex.reason());
    }

    @Test
    void missingTaskFenceFailsClosedWithTypedReason() {
        AgentTaskMapper mapper = mock(AgentTaskMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(71L)).thenReturn(null);
        AgentRunExecutionLeaseService service = new AgentRunExecutionLeaseService(mapper, "instance-a", 30_000L);

        AgentRunExecutionLeaseService.StaleExecutionFenceException ex = assertThrows(
                AgentRunExecutionLeaseService.StaleExecutionFenceException.class,
                () -> service.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L),
                        LocalDateTime.of(2026, 7, 23, 10, 0)));
        assertEquals(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.TASK_NOT_FOUND, ex.reason());
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStatus("running");
        task.setExecutionEpoch(0L);
        return task;
    }
}
