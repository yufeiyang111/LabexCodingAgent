package com.labex.labexagent.workspace;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentProjectCheckoutLease;
import com.labex.mapper.AgentProjectCheckoutLeaseMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Database-backed, fenced checkout lease. Tasks may run concurrently only when their resolved
 * workspace paths differ (for example a managed background worktree).
 */
@Service
public class ProjectCheckoutLeaseService {
    private final AgentProjectCheckoutLeaseMapper mapper;
    private final String instanceId;
    private final long leaseDurationMs;

    public ProjectCheckoutLeaseService(AgentProjectCheckoutLeaseMapper mapper,
                                       @Value("${labex-agent.instance-id:}") String configuredInstanceId,
                                       @Value("${labex-agent.project-checkout-lease-duration-ms:30000}") long leaseDurationMs) {
        this.mapper = mapper;
        String candidate = configuredInstanceId == null ? "" : configuredInstanceId.trim();
        this.instanceId = candidate.isBlank() ? "labex-agent-checkout-" + UUID.randomUUID() : candidate;
        this.leaseDurationMs = Math.max(5_000L, leaseDurationMs);
    }

    public AcquireResult acquire(Long taskId, Integer projectId, Path workspaceRoot) {
        return acquire(taskId, projectId, workspaceRoot, LocalDateTime.now());
    }

    public AcquireResult acquire(Long taskId, Integer projectId, Path workspaceRoot, LocalDateTime now) {
        if (taskId == null || projectId == null || workspaceRoot == null) {
            return AcquireResult.invalidRequest();
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        String workspacePath = workspaceRoot.toAbsolutePath().normalize().toString();
        String checkoutKey = checkoutKey(projectId, workspacePath);
        AgentProjectCheckoutLease existing = mapper.selectById(checkoutKey);
        LocalDateTime expiresAt = expiresAt(effectiveNow);
        if (existing == null) {
            AgentProjectCheckoutLease created = lease(checkoutKey, projectId, workspacePath, taskId, 1L,
                    effectiveNow, expiresAt);
            try {
                if (mapper.insert(created) == 1) {
                    return AcquireResult.acquired(toLease(created));
                }
            } catch (DuplicateKeyException ignored) {
                // Another instance inserted the same checkout row. Re-read below and return its owner.
            }
            existing = mapper.selectById(checkoutKey);
            if (existing == null) {
                return AcquireResult.busy(null);
            }
        }
        if (sameActiveLease(existing, taskId, effectiveNow)) {
            CheckoutLease current = toLease(existing);
            return renew(current, effectiveNow) ? AcquireResult.acquired(current.withExpiresAt(expiresAt))
                    : AcquireResult.busy(existing.getTaskId());
        }
        if (isExpired(existing, effectiveNow)) {
            long nextEpoch = valueOrZero(existing.getLeaseEpoch()) + 1L;
            int updated = mapper.update(null, new UpdateWrapper<AgentProjectCheckoutLease>()
                    .eq("checkout_key", checkoutKey)
                    .le("lease_expires_at", effectiveNow)
                    .set("project_id", projectId)
                    .set("workspace_path", workspacePath)
                    .set("task_id", taskId)
                    .set("lease_owner", instanceId)
                    .set("lease_epoch", nextEpoch)
                    .set("lease_expires_at", expiresAt)
                    .set("heartbeat_at", effectiveNow)
                    .set("update_time", effectiveNow));
            if (updated == 1) {
                return AcquireResult.acquired(new CheckoutLease(checkoutKey, taskId, instanceId, nextEpoch, expiresAt));
            }
            AgentProjectCheckoutLease current = mapper.selectById(checkoutKey);
            return AcquireResult.busy(current == null ? null : current.getTaskId());
        }
        return AcquireResult.busy(existing.getTaskId());
    }

    public boolean renew(CheckoutLease lease) {
        return renew(lease, LocalDateTime.now());
    }

    public boolean renew(CheckoutLease lease, LocalDateTime now) {
        if (lease == null) return false;
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        return mapper.update(null, new UpdateWrapper<AgentProjectCheckoutLease>()
                .eq("checkout_key", lease.checkoutKey())
                .eq("task_id", lease.taskId())
                .eq("lease_owner", lease.owner())
                .eq("lease_epoch", lease.epoch())
                .gt("lease_expires_at", effectiveNow)
                .set("lease_expires_at", expiresAt(effectiveNow))
                .set("heartbeat_at", effectiveNow)
                .set("update_time", effectiveNow)) == 1;
    }

    public void release(CheckoutLease lease) {
        if (lease == null) return;
        LocalDateTime now = LocalDateTime.now();
        mapper.update(null, new UpdateWrapper<AgentProjectCheckoutLease>()
                .eq("checkout_key", lease.checkoutKey())
                .eq("task_id", lease.taskId())
                .eq("lease_owner", lease.owner())
                .eq("lease_epoch", lease.epoch())
                .set("lease_expires_at", now)
                .set("heartbeat_at", now)
                .set("update_time", now));
    }

    public boolean isAvailable(Integer projectId, Path workspaceRoot) {
        if (projectId == null || workspaceRoot == null) return false;
        AgentProjectCheckoutLease lease = mapper.selectById(checkoutKey(projectId, workspaceRoot));
        return lease == null || isExpired(lease, LocalDateTime.now());
    }

    public String checkoutKey(Integer projectId, Path workspaceRoot) {
        if (projectId == null || workspaceRoot == null) throw new IllegalArgumentException("project checkout is required");
        return checkoutKey(projectId, workspaceRoot.toAbsolutePath().normalize().toString());
    }

    private String checkoutKey(Integer projectId, String workspacePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((projectId + "\n" + workspacePath).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to derive project checkout key", exception);
        }
    }

    private AgentProjectCheckoutLease lease(String checkoutKey, Integer projectId, String workspacePath, Long taskId,
                                            long epoch, LocalDateTime now, LocalDateTime expiresAt) {
        AgentProjectCheckoutLease lease = new AgentProjectCheckoutLease();
        lease.setCheckoutKey(checkoutKey); lease.setProjectId(projectId); lease.setWorkspacePath(workspacePath);
        lease.setTaskId(taskId); lease.setLeaseOwner(instanceId); lease.setLeaseEpoch(epoch);
        lease.setLeaseExpiresAt(expiresAt); lease.setHeartbeatAt(now); lease.setCreateTime(now); lease.setUpdateTime(now);
        return lease;
    }

    private CheckoutLease toLease(AgentProjectCheckoutLease row) {
        return new CheckoutLease(row.getCheckoutKey(), row.getTaskId(), row.getLeaseOwner(),
                valueOrZero(row.getLeaseEpoch()), row.getLeaseExpiresAt());
    }

    private boolean sameActiveLease(AgentProjectCheckoutLease row, Long taskId, LocalDateTime now) {
        return row != null && taskId.equals(row.getTaskId()) && instanceId.equals(row.getLeaseOwner())
                && row.getLeaseExpiresAt() != null && row.getLeaseExpiresAt().isAfter(now);
    }

    private boolean isExpired(AgentProjectCheckoutLease row, LocalDateTime now) {
        return row.getLeaseExpiresAt() == null || !row.getLeaseExpiresAt().isAfter(now);
    }

    private LocalDateTime expiresAt(LocalDateTime now) { return now.plusNanos(leaseDurationMs * 1_000_000L); }
    private long valueOrZero(Long value) { return value == null ? 0L : value; }

    public record CheckoutLease(String checkoutKey, Long taskId, String owner, long epoch, LocalDateTime expiresAt) {
        CheckoutLease withExpiresAt(LocalDateTime updatedExpiresAt) {
            return new CheckoutLease(checkoutKey, taskId, owner, epoch, updatedExpiresAt);
        }
    }

    public record AcquireResult(CheckoutLease lease, Long blockingTaskId, boolean invalid) {
        public static AcquireResult acquired(CheckoutLease lease) { return new AcquireResult(lease, null, false); }
        public static AcquireResult busy(Long taskId) { return new AcquireResult(null, taskId, false); }
        public static AcquireResult invalidRequest() { return new AcquireResult(null, null, true); }
        public boolean acquired() { return lease != null; }
    }
}
