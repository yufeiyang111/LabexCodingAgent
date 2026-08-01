package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fences concurrent agent workers with a durable owner, epoch, and expiring lease. */
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
}
