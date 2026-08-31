package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fences concurrent agent workers with a durable owner, epoch, and expiring lease.
 *
 * <p>Control-plane lifecycle APIs (queue initialization, transactional dispatch claims,
 * interaction expiry, cancellation and scheduler takeover) are deliberately NOT fenced by
 * {@link #requireActiveFence}: they remain separately named and validate their own
 * ownership/CAS contract. The fence is the executor-side proof for durable fact writes only.
 */
@Service
public class AgentRunExecutionLeaseService {
    private final AgentTaskMapper taskMapper;
    private final String instanceId;
    private final long leaseDurationMs;

    public AgentRunExecutionLeaseService(AgentTaskMapper taskMapper,
                                         @Value("${labex-agent.instance-id:}") String configuredInstanceId,
                                         @Value("${labex-agent.execution-lease-duration-ms:30000}") long leaseDurationMs) {
        this.taskMapper = taskMapper;
        String candidate = configuredInstanceId == null ? "" : configuredInstanceId.trim();
        this.instanceId = candidate.isBlank() ? "labex-agent-" + UUID.randomUUID() : candidate;
        this.leaseDurationMs = Math.max(5_000L, leaseDurationMs);
    }

    public ExecutionLease acquire(Long taskId) {
        return acquire(taskId, LocalDateTime.now());
    }

    public ExecutionLease acquire(Long taskId, LocalDateTime now) {
        if (taskId == null) {
            return null;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null || isTerminal(task.getStatus()) || instanceId.isBlank()) {
            return null;
        }
        boolean activeLease = task.getExecutionOwner() != null
                && !task.getExecutionOwner().isBlank()
                && task.getExecutionLeaseExpiresAt() != null
                && task.getExecutionLeaseExpiresAt().isAfter(effectiveNow);
        if (activeLease) {
            return null;
        }
        long epoch = valueOrZero(task.getExecutionEpoch()) + 1L;
        LocalDateTime expiresAt = effectiveNow.plusNanos(leaseDurationMs * 1_000_000L);
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .notIn("status", "completed", "failed", "cancelled")
                .and(wrapper -> wrapper.isNull("execution_owner")
                        .or().isNull("execution_lease_expires_at")
                        .or().le("execution_lease_expires_at", effectiveNow))
                .set("execution_owner", instanceId)
                .set("execution_epoch", epoch)
                .set("execution_lease_expires_at", expiresAt)
                .set("execution_heartbeat_at", effectiveNow);
        if (taskMapper.update(null, update) != 1) {
            return null;
        }
        return new ExecutionLease(taskId, instanceId, epoch, expiresAt);
    }

    @Transactional(timeout = 3)
    public boolean renew(ExecutionLease lease) {
        return renew(lease, LocalDateTime.now());
    }

    public boolean renew(ExecutionLease lease, LocalDateTime now) {
        if (lease == null) {
            return false;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        LocalDateTime expiresAt = effectiveNow.plusNanos(leaseDurationMs * 1_000_000L);
        int updated = taskMapper.update(null, new UpdateWrapper<AgentTask>()
                .eq("task_id", lease.taskId())
                .eq("execution_owner", lease.owner())
                .eq("execution_epoch", lease.epoch())
                .gt("execution_lease_expires_at", effectiveNow)
                .set("execution_lease_expires_at", expiresAt)
                .set("execution_heartbeat_at", effectiveNow));
        return updated == 1;
    }

    public void release(ExecutionLease lease) {
        release(lease, LocalDateTime.now());
    }

    public void release(ExecutionLease lease, LocalDateTime now) {
        if (lease == null) {
            return;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        taskMapper.update(null, new UpdateWrapper<AgentTask>()
                .eq("task_id", lease.taskId())
                .eq("execution_owner", lease.owner())
                .eq("execution_epoch", lease.epoch())
                .set("execution_owner", null)
                .set("execution_lease_expires_at", effectiveNow)
                .set("execution_heartbeat_at", effectiveNow));
    }

    public boolean hasActiveLease(AgentTask task, LocalDateTime now) {
        if (task == null || task.getExecutionOwner() == null || task.getExecutionOwner().isBlank()
                || task.getExecutionLeaseExpiresAt() == null) {
            return false;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        return task.getExecutionLeaseExpiresAt().isAfter(effectiveNow);
    }

    /**
     * Requires an active execution fence: the durable task row must be owned by
     * {@code fence.owner()}, must run at exactly {@code fence.epoch()} and must hold an
     * unexpired execution lease. The check is a single database predicate.
     *
     * <p>This is a read/verify-only contract: it never writes and can never cause partial
     * persistence. When the predicate fails, a typed {@link StaleExecutionFenceException}
     * is thrown whose {@link StaleExecutionFenceException.Reason} distinguishes a stale
     * owner, a stale epoch and an expired lease without leaking row internals.
     *
     * <p>Control-plane lifecycle APIs (queue initialization, transactional dispatch
     * claims, interaction expiry, cancellation and scheduler takeover) are deliberately
     * NOT fenced by this predicate; they remain separately named and validate their own
     * ownership/CAS contract.
     *
     * @param fence the immutable owner/epoch/task identity the executor claims to hold
     * @param now   the reference time used for lease-expiry comparison
     * @throws StaleExecutionFenceException when the executor no longer holds the active lease
     */
    public void requireActiveFence(ExecutionFence fence, LocalDateTime now) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        if (fence == null || fence.taskId() == null || fence.owner() == null || fence.owner().isBlank()) {
            throw new StaleExecutionFenceException(StaleExecutionFenceException.Reason.INVALID_FENCE);
        }
        Long active = taskMapper.selectCount(new QueryWrapper<AgentTask>()
                .eq("task_id", fence.taskId())
                .eq("execution_owner", fence.owner())
                .eq("execution_epoch", fence.epoch())
                .gt("execution_lease_expires_at", effectiveNow));
        if (active != null && active == 1L) {
            return;
        }
        throw new StaleExecutionFenceException(reasonOf(fence, effectiveNow));
    }

    /**
     * Requires a fence while holding the task row lock for the caller's write transaction.
     *
     * <p>Executor-side telemetry and snapshot writes use this stronger form so a lease
     * takeover cannot pass the fence check between validation and the durable write. The
     * caller must run inside a transaction; otherwise the row lock is released before its
     * subsequent write.
     */
    @Transactional
    public void requireActiveFenceForWrite(ExecutionFence fence, LocalDateTime now) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        validateFenceShape(fence);
        AgentTask task = taskMapper.selectByTaskIdForUpdate(fence.taskId());
        if (isActiveFence(task, fence, effectiveNow)) {
            return;
        }
        throw new StaleExecutionFenceException(reasonOf(fence, task, effectiveNow));
    }

    private StaleExecutionFenceException.Reason reasonOf(ExecutionFence fence, LocalDateTime now) {
        return reasonOf(fence, taskMapper.selectById(fence.taskId()), now);
    }

    private StaleExecutionFenceException.Reason reasonOf(ExecutionFence fence, AgentTask task,
                                                         LocalDateTime now) {
        if (task == null) {
            return StaleExecutionFenceException.Reason.TASK_NOT_FOUND;
        }
        if (task.getExecutionOwner() == null || !task.getExecutionOwner().equals(fence.owner())) {
            return StaleExecutionFenceException.Reason.STALE_OWNER;
        }
        if (task.getExecutionEpoch() == null || task.getExecutionEpoch() != fence.epoch()) {
            return StaleExecutionFenceException.Reason.STALE_EPOCH;
        }
        if (task.getExecutionLeaseExpiresAt() == null || !task.getExecutionLeaseExpiresAt().isAfter(now)) {
            return StaleExecutionFenceException.Reason.EXPIRED_LEASE;
        }
        return StaleExecutionFenceException.Reason.STALE_FENCE;
    }

    private boolean isActiveFence(AgentTask task, ExecutionFence fence, LocalDateTime now) {
        return task != null
                && fence != null
                && fence.taskId().equals(task.getTaskId())
                && fence.owner().equals(task.getExecutionOwner())
                && task.getExecutionEpoch() != null
                && task.getExecutionEpoch() == fence.epoch()
                && task.getExecutionLeaseExpiresAt() != null
                && task.getExecutionLeaseExpiresAt().isAfter(now);
    }

    private void validateFenceShape(ExecutionFence fence) {
        if (fence == null || fence.taskId() == null || fence.owner() == null || fence.owner().isBlank()) {
            throw new StaleExecutionFenceException(StaleExecutionFenceException.Reason.INVALID_FENCE);
        }
    }

    public String instanceId() { return instanceId; }
    public long leaseDurationMs() { return leaseDurationMs; }

    private boolean isTerminal(String status) {
        return "completed".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    public record ExecutionLease(Long taskId, String owner, long epoch, LocalDateTime expiresAt) {
    }

    /**
     * Typed failure raised when an executor no longer holds the active lease its fence
     * claims. Read/verify-only: no persistence happens inside this failure path.
     */
    public static final class StaleExecutionFenceException extends RuntimeException {
        private final Reason reason;

        public StaleExecutionFenceException(Reason reason) {
            super("Stale execution fence: " + reason.code());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }

        /** Safe, stable reason codes distinguishing the failing fence dimension. */
        public enum Reason {
            TASK_NOT_FOUND("task-not-found"),
            STALE_OWNER("stale-owner"),
            STALE_EPOCH("stale-epoch"),
            EXPIRED_LEASE("expired-lease"),
            STALE_FENCE("stale-fence"),
            INVALID_FENCE("invalid-fence");

            private final String code;

            Reason(String code) {
                this.code = code;
            }

            public String code() {
                return code;
            }
        }
    }
}
