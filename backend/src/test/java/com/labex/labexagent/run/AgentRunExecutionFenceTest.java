package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunOutbox;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ExecutionFence 契约：active lease 通过、stale owner / stale epoch / expired lease 返回
 * typed failure。writer 路径的集成负向断言（lifecycle state、event 追加）在 Task 0.5 的
 * AgentRunLifecycleServiceTest 中覆盖，本文件只验证 fence 本身与不被 fence 的控制面边界。
 */
class AgentRunExecutionFenceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 23, 10, 0);

    @Test
    void activeFencePassesForMatchingOwnerEpochAndUnexpiredLease() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        leases.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L), NOW);
    }

    @Test
    void staleOwnerReturnsTypedFailure() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(task("running", "instance-b", 4L, NOW.plusMinutes(1)));
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        assertThatThrownBy(() -> leases.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L), NOW))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .extracting(ex -> ((AgentRunExecutionLeaseService.StaleExecutionFenceException) ex).reason())
                .isEqualTo(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_OWNER);
    }

    @Test
    void staleEpochReturnsTypedFailure() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(task("running", "instance-a", 4L, NOW.plusMinutes(1)));
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        assertThatThrownBy(() -> leases.requireActiveFence(new ExecutionFence(71L, "instance-a", 3L), NOW))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .extracting(ex -> ((AgentRunExecutionLeaseService.StaleExecutionFenceException) ex).reason())
                .isEqualTo(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_EPOCH);
    }

    @Test
    void expiredLeaseReturnsTypedFailure() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(task("running", "instance-a", 4L, NOW.minusSeconds(1)));
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        assertThatThrownBy(() -> leases.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L), NOW))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .extracting(ex -> ((AgentRunExecutionLeaseService.StaleExecutionFenceException) ex).reason())
                .isEqualTo(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.EXPIRED_LEASE);
    }

    @Test
    void leaseExpiringExactlyAtNowIsNotActiveAndFailsClosed() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(task("running", "instance-a", 4L, NOW));
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        assertThatThrownBy(() -> leases.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L), NOW))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .extracting(ex -> ((AgentRunExecutionLeaseService.StaleExecutionFenceException) ex).reason())
                .isEqualTo(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.EXPIRED_LEASE);
    }

    @Test
    void missingTaskFailsClosedWithTypedFailure() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(null);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        assertThatThrownBy(() -> leases.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L), NOW))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .extracting(ex -> ((AgentRunExecutionLeaseService.StaleExecutionFenceException) ex).reason())
                .isEqualTo(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.TASK_NOT_FOUND);
    }

    @Test
    void malformedFenceFailsClosedWithoutQuerying() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        assertThatThrownBy(() -> leases.requireActiveFence(null, NOW))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .extracting(ex -> ((AgentRunExecutionLeaseService.StaleExecutionFenceException) ex).reason())
                .isEqualTo(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.INVALID_FENCE);
        assertThatThrownBy(() -> leases.requireActiveFence(new ExecutionFence(71L, null, 4L), NOW))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .extracting(ex -> ((AgentRunExecutionLeaseService.StaleExecutionFenceException) ex).reason())
                .isEqualTo(AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.INVALID_FENCE);
        verify(tasks, never()).selectCount(any());
    }

    @Test
    void activeLeasePredicateIsOneReadOnlyDatabaseQuery() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);

        leases.requireActiveFence(new ExecutionFence(71L, "instance-a", 4L), NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<AgentTask>> wrapper = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(tasks, times(1)).selectCount(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment())
                .contains("execution_owner")
                .contains("execution_epoch")
                .contains("execution_lease_expires_at");
        verify(tasks, never()).update(any(), any());
        verify(tasks, never()).insert(any());
    }

    @Test
    void claimDispatchAndRecoveryRequireNoActiveLease() {
        List<AgentRunEvent> inserted = new ArrayList<>();
        AgentTask task = task("waiting_user", "instance-b", 4L, NOW.plusMinutes(5));
        AgentRunLifecycleService lifecycle = lifecycle(task, inserted, 0);

        AgentRunLifecycleService.DispatchClaim dispatch = lifecycle.claimDispatch(
                71L, AgentRunState.WAITING_USER, AgentRunState.RECOVERING, "RUN_INTERACTION_RESUME_QUEUED",
                Map.of(), "Resuming after user response", "A persisted user response is ready",
                "fence-claim-1", "instance-a", 30_000L);
        AgentRunLifecycleService.RecoveryClaim recovery = lifecycle.claimRecovery(
                71L, AgentRunState.WAITING_USER, "instance-a", 30_000L);

        assertThat(dispatch).isNull();
        assertThat(recovery).isNull();
        assertThat(inserted).isEmpty();
    }

    @Test
    void renewIsFencedToTheHeldOwnerEpochAndLease() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.update(isNull(), any())).thenReturn(1);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunExecutionLeaseService.ExecutionLease held =
                new AgentRunExecutionLeaseService.ExecutionLease(71L, "instance-a", 4L, NOW.plusMinutes(1));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<UpdateWrapper<AgentTask>> wrapper = ArgumentCaptor.forClass(UpdateWrapper.class);
        leases.renew(held, NOW);

        verify(tasks).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment())
                .contains("execution_owner")
                .contains("execution_epoch")
                .contains("execution_lease_expires_at");
    }

    private AgentRunLifecycleService lifecycle(AgentTask task, List<AgentRunEvent> inserted, int updateResult) {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(tasks.update(isNull(), any())).thenReturn(updateResult);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        when(events.selectOne(any())).thenAnswer(invocation -> inserted.isEmpty() ? null : inserted.get(0));
        when(events.selectMaxSequenceByTaskId(71L)).thenReturn(0L);
        doAnswer(invocation -> {
            AgentRunEvent event = invocation.getArgument(0);
            event.setEventId(1L);
            inserted.add(event);
            return 1;
        }).when(events).insert(any(AgentRunEvent.class));
        AgentRunOutboxMapper outbox = mock(AgentRunOutboxMapper.class);
        when(outbox.insert(any(AgentRunOutbox.class))).thenReturn(1);
        return new AgentRunLifecycleService(tasks, events, outbox);
    }

    private AgentTask task(String status, String owner, long epoch, LocalDateTime leaseExpiresAt) {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setSessionId("session-1");
        task.setMode("agent");
        task.setStatus(status);
        task.setExecutionOwner(owner);
        task.setExecutionEpoch(epoch);
        task.setExecutionLeaseExpiresAt(leaseExpiresAt);
        task.setExecutionHeartbeatAt(leaseExpiresAt.minusSeconds(5));
        task.setRunVersion(1L);
        task.setLastEventSequence(0L);
        return task;
    }
}
