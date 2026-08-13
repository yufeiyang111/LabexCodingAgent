package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 运行模式切换的唯一 fenced 入口：先把 mode 以 CAS 方式持久化到 {@code t_agent_task}，
 * 再追加 durable {@code RUN_MODE_CHANGED} 事件，最后才更新内存中的 {@link AgentContext}。
 *
 * <p>内存上下文是派生视图，绝不能先于持久化生效；同一幂等键重放只收敛内存，不产生重复副作用。
 * 切换失败（fence 过期、任务缺失、任务不在 plan 模式）抛出 typed failure，不产生部分持久化。
 */
@Service
public class AgentRunModeService {
    public static final String RUN_MODE_CHANGED = "RUN_MODE_CHANGED";

    private final AgentTaskMapper taskMapper;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunExecutionLeaseService leaseService;

    public AgentRunModeService(AgentTaskMapper taskMapper,
                               AgentRunLifecycleService lifecycleService,
                               AgentRunExecutionLeaseService leaseService) {
        this.taskMapper = taskMapper;
        this.lifecycleService = lifecycleService;
        this.leaseService = leaseService;
    }

    /** 稳定幂等键：同一 task 的 plan -> build 切换无论重试多少次都只产生一次 durable 副作用。 */
    public static String planToBuildKey(Long taskId) {
        return "mode-plan-to-build-" + String.valueOf(taskId);
    }

    /**
     * 把任务从 plan 模式原子切换到 build 模式。
     *
     * <p>顺序固定为：验证 fence -&gt; 幂等重放检查 -&gt; CAS 更新 task.mode -&gt; 持久化
     * {@code RUN_MODE_CHANGED} 事件 -&gt; 更新内存 task/context。任何一步失败都整体回滚。
     *
     * <p>内存 context 是派生视图：本方法在事务体内更新，若提交失败事务回滚、异常向上传播，
     * 调用方（PlanExitTool）的 tool 调用失败，run 随即终止，未提交的内存 context 不会被继续消费；
     * 事务感知路径（Spring 代理）下 context 更新推迟到 afterCommit 后才执行。
     *
     * @param taskId         需要切换模式的任务
     * @param fence          执行者持有的 owner/epoch/lease 证明
     * @param idempotencyKey 稳定幂等键，重放时只收敛内存不重复写入
     * @param context        切换成功后同步更新其 mode 的内存上下文；可为 null
     * @return 持久化的 RUN_MODE_CHANGED 事件；同幂等键重放时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunEvent transitionPlanToBuild(Long taskId, ExecutionFence fence,
                                               String idempotencyKey, AgentContext context) {
        if (taskId == null) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (fence == null) {
            throw new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                    AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.INVALID_FENCE);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        this.leaseService.requireActiveFence(fence, LocalDateTime.now());
        if (this.lifecycleService.hasEvent(taskId, idempotencyKey)) {
            // 幂等重放：durable 切换已存在（可能是并发双 plan_exit 的先提交者），
            // 只收敛内存派生视图，不再写任何事实。
            if (context != null) {
                context.setMode("build");
            }
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = this.taskMapper.update(null, new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .eq("mode", "plan")
                .eq("execution_owner", fence.owner())
                .eq("execution_epoch", fence.epoch())
                .gt("execution_lease_expires_at", now)
                .set("mode", "build")
                .set("update_time", now));
        if (updated != 1) {
            // 并发双 plan_exit 的落败方：CAS 是唯一写者，先提交者的 durable 切换已完成，
            // 这里以幂等重放收敛，避免把合法的并发切换误判为非法状态。
            if (this.lifecycleService.hasEvent(taskId, idempotencyKey)) {
                if (context != null) {
                    context.setMode("build");
                }
                return null;
            }
            throw failureOf(taskId, fence, now);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("epoch", fence.epoch());
        payload.put("previousMode", "plan");
        payload.put("nextMode", "build");
        payload.put("idempotencyKey", idempotencyKey);
        AgentRunEvent event = this.lifecycleService.appendEvent(
                fence, taskId, RUN_MODE_CHANGED, payload, idempotencyKey);
        AgentTask durable = this.taskMapper.selectById(taskId);
        if (durable != null) {
            durable.setMode("build");
        }
        applyBuildModeToContext(context);
        return event;
    }

    /**
     * 内存 context 只在 durable 切换成功后才更新；事务感知路径把更新推迟到 afterCommit，
     * 避免事务回滚后内存状态领先于 durable 事实。非事务调用（单元测试直构）直接同步更新。
     */
    private static void applyBuildModeToContext(AgentContext context) {
        if (context == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    context.setMode("build");
                }
            });
        } else {
            context.setMode("build");
        }
    }

    private RuntimeException failureOf(Long taskId, ExecutionFence fence, LocalDateTime now) {
        AgentTask task = this.taskMapper.selectById(taskId);
        if (task == null) {
            return new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                    AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.TASK_NOT_FOUND);
        }
        if (!"plan".equalsIgnoreCase(task.getMode())) {
            return new IllegalStateException(
                    "Agent run is not in plan mode; cannot switch to build mode: " + taskId);
        }
        return new AgentRunExecutionLeaseService.StaleExecutionFenceException(reasonOf(fence, task, now));
    }

    private AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason reasonOf(
            ExecutionFence fence, AgentTask task, LocalDateTime now) {
        if (task.getExecutionOwner() == null || !task.getExecutionOwner().equals(fence.owner())) {
            return AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_OWNER;
        }
        if (task.getExecutionEpoch() == null || task.getExecutionEpoch() != fence.epoch()) {
            return AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_EPOCH;
        }
        if (task.getExecutionLeaseExpiresAt() == null || !task.getExecutionLeaseExpiresAt().isAfter(now)) {
            return AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.EXPIRED_LEASE;
        }
        return AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_FENCE;
    }
}
