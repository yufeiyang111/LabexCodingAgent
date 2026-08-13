package com.labex.labexagent.run;

import com.labex.entity.AgentTask;
import com.labex.service.StudentProjectService;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AgentRunTakeoverScheduler {
    private final AgentRunExecutionLeaseService leases;
    private final AgentRunLifecycleService lifecycle;
    private final AgentLoopEngine engine;
    private final StudentProjectService projectService;

    public AgentRunTakeoverScheduler(AgentRunExecutionLeaseService leases, AgentRunLifecycleService lifecycle,
                                     @Lazy AgentLoopEngine engine, StudentProjectService projectService) {
        this.leases = leases;
        this.lifecycle = lifecycle;
        this.engine = engine;
        this.projectService = projectService;
    }

    public boolean takeover(AgentTask task) {
        if (task == null || !safe(task.getStatus())) {
            return false;
        }
        AgentRunState expected = AgentRunState.fromPersistedStatus(task.getStatus());
        if (!projectExists(task)) {
            lifecycle.transitionIfCurrent(
                    task.getTaskId(),
                    expected,
                    AgentRunState.FAILED,
                    "RUN_RECOVERY_PROJECT_MISSING",
                    Map.of("reason", "Project no longer exists",
                            "projectId", String.valueOf(task.getProjectId())),
                    "Recovery failed",
                    "The project no longer exists; the interrupted run cannot be resumed.",
                    "recovery-project-missing-" + task.getTaskId());
            return false;
        }
        AgentRunExecutionLeaseService.ExecutionLease lease = claimLease(task, expected);
        if (lease == null) {
            return false;
        }
        if (!hasDurableContinuationContext(task)) {
            return failClaimedTakeover(task, lease,
                    "Missing durable conversation or task ownership metadata");
        }
        try {
            AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task,
                    "An expired execution lease was taken over. Reassess the workspace and continue without replaying uncertain side effects.");
            engine.resume(task.getStudentId(), task.getProjectId(), request, task.getTaskId(), true, lease);
            return true;
        } catch (RuntimeException failure) {
            return failClaimedTakeover(task, lease, "Unable to enqueue recovered task: " + failure.getMessage());
        }
    }

    /**
     * 通过生命周期/租约权威领取一个新 execution epoch，并产生一次幂等 dispatch 事件。
     * queued/preparing/running 走 claimRecovery（进入 RECOVERING）；搁浅的 recovering worker
     * 已经处于 RECOVERING，状态机不允许自环，因此直接 claim RECOVERING -> PREPARING，
     * 在同一事务内递增 epoch、重建租约并写入带幂等键的 RUN_RECOVERY_TAKEOVER 事件。
     *
     * <p>幂等键必须与 claim 实际分配的 epoch 一致：key 从本方法收到的 task 行状态派生
     * （{@code execution_epoch + 1}），而 claimDispatch 从锁定行派生 epoch。调用方
     * （AgentRunRecoveryService 的周期 reconciler）在调用前已重新读取当前行，因此两者
     * 在 double-death 竞态下也不会漂移；并发实例即使拿到等价快照也共享同一个 key，
     * 由 claimDispatch 的幂等键回放校验保证恰好一次 dispatch。
     */
    private AgentRunExecutionLeaseService.ExecutionLease claimLease(AgentTask task, AgentRunState expected) {
        if (expected == AgentRunState.RECOVERING) {
            long epoch = valueOrZero(task.getExecutionEpoch()) + 1L;
            AgentRunLifecycleService.DispatchClaim dispatch = lifecycle.claimDispatch(
                    task.getTaskId(),
                    AgentRunState.RECOVERING,
                    AgentRunState.PREPARING,
                    "RUN_RECOVERY_TAKEOVER",
                    Map.of("owner", leases.instanceId(), "epoch", epoch,
                            "reason", "Stranded recovering worker with an expired execution lease"),
                    "Recovering after expired lease",
                    "Recovery takeover claimed",
                    "recovery-takeover-" + task.getTaskId() + "-" + epoch,
                    leases.instanceId(),
                    leases.leaseDurationMs());
            return dispatch == null ? null : dispatch.lease();
        }
        AgentRunLifecycleService.RecoveryClaim claim = lifecycle.claimRecovery(
                task.getTaskId(), expected, leases.instanceId(), leases.leaseDurationMs());
        if (claim == null) {
            return null;
        }
        return new AgentRunExecutionLeaseService.ExecutionLease(
                task.getTaskId(), claim.owner(), claim.epoch(), claim.expiresAt());
    }

    private boolean failClaimedTakeover(AgentTask task, AgentRunExecutionLeaseService.ExecutionLease lease,
                                        String reason) {
        lifecycle.transition(
                task.getTaskId(),
                AgentRunState.FAILED,
                "RUN_RECOVERY_TAKEOVER_FAILED",
                Map.of("reason", reason),
                "Recovery takeover failed",
                reason,
                "recovery-takeover-failed-" + task.getTaskId() + "-" + lease.epoch());
        leases.release(lease);
        return false;
    }

    private boolean projectExists(AgentTask task) {
        if (task == null || task.getStudentId() == null || task.getProjectId() == null || projectService == null) {
            return false;
        }
        return projectService.getOwnedProject(task.getStudentId(), task.getProjectId()) != null;
    }

    private boolean hasDurableContinuationContext(AgentTask task) {
        return task.getTaskId() != null && task.getStudentId() != null && task.getProjectId() != null
                && task.getConversationId() != null && !task.getConversationId().isBlank();
    }

    private boolean safe(String status) {
        return "queued".equals(status) || "preparing".equals(status) || "running".equals(status)
                || "recovering".equals(status);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
