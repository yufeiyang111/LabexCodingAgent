package com.labex.labexagent.run;

import com.labex.entity.AgentTask;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 子代理前台等待的终态注册表：launcher 以 t_agent_subagent 行 ID 注册 future，
 * 引擎在子任务创建后绑定 taskId、在运行 finally 中按 taskId 触发 finisher 并完成 future。
 * 仅适用于同 JVM 前台等待；进程重启后未完成的 future 由 TaskTool 超时路径兜底。
 */
@Service
public class SubagentCompletionRegistry {

    /** 行注册时的持久化回调：把 child_task_id / parent_task_id 写回两张表。 */
    public interface TaskBinder {
        void bind(Long subagentRowId, AgentTask task);
    }

    /** 子任务终态后的同步回调（行状态、摘要、父级镜像）。 */
    public interface TaskFinisher {
        /**
         * @param childTaskId 子任务的 AgentTask ID
         * @return true 表示子代理行已收束到真终态（父任务可解除阻塞）；
         *         false 表示仍处于可恢复等待态（注册保持，恢复后的再次 finished 会重试）。
         */
        boolean finish(Long childTaskId);
    }

    private static final class Entry {
        final CompletableFuture<Void> future;
        volatile Long taskId;
        final TaskBinder binder;
        final TaskFinisher finisher;

        Entry(CompletableFuture<Void> future, TaskBinder binder, TaskFinisher finisher) {
            this.future = future;
            this.binder = binder;
            this.finisher = finisher;
        }
    }

    private final Map<Long, Entry> byRowId = new ConcurrentHashMap<>();
    private final Map<Long, Long> taskIdIndex = new ConcurrentHashMap<>();

    public CompletableFuture<Void> register(Long subagentRowId, TaskBinder binder) {
        return register(subagentRowId, binder, null);
    }

    public CompletableFuture<Void> register(Long subagentRowId, TaskBinder binder, TaskFinisher finisher) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        byRowId.put(subagentRowId, new Entry(future, binder, finisher));
        return future;
    }

    /** 引擎在子任务创建后调用；幂等。 */
    public void onCreated(Long subagentRowId, AgentTask task) {
        if (subagentRowId == null || task == null || task.getTaskId() == null) return;
        Entry entry = byRowId.get(subagentRowId);
        if (entry == null) return;
        synchronized (entry) {
            entry.binder.bind(subagentRowId, task);
            entry.taskId = task.getTaskId();
            taskIdIndex.put(task.getTaskId(), subagentRowId);
        }
    }

    /** 引擎 runLoop finally 调用；未注册的 taskId 静默忽略（普通任务零开销）。 */
    public void finished(Long taskId) {
        if (taskId == null) return;
        Long rowId = taskIdIndex.get(taskId);
        if (rowId == null) return;
        Entry entry = byRowId.get(rowId);
        if (entry == null) return;
        synchronized (entry) {
            try {
                // finisher 只有在子任务到达真终态时才返回 true：
                // - true  → 行已收束，解除父任务等待并注销注册；
                // - false → 可恢复等待态（审批/用户输入/租约丢失），保持注册，
                //           等恢复路径再次跑完时二次触发，父任务继续等待或转后台。
                boolean finalized = entry.finisher == null || entry.finisher.finish(taskId);
                if (!finalized) {
                    return;
                }
            } catch (RuntimeException failure) {
                // 终态同步失败不再静默吞掉：如实上报，父任务看到精确失败原因。
                taskIdIndex.remove(taskId);
                byRowId.remove(rowId);
                entry.future.completeExceptionally(failure);
                return;
            }
            taskIdIndex.remove(taskId);
            byRowId.remove(rowId);
            entry.future.complete(null);
        }
    }

    /** 派发失败（入队被拒/前置校验异常）时由 launcher 主动完成并清理。 */
    public void failed(Long subagentRowId, Throwable error) {
        if (subagentRowId == null) return;
        Entry entry = byRowId.remove(subagentRowId);
        if (entry != null) entry.future.completeExceptionally(error);
    }

    public boolean isPending(Long subagentRowId) {
        return byRowId.containsKey(subagentRowId);
    }
}
