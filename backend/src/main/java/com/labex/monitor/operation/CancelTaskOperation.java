package com.labex.monitor.operation;

import com.labex.labexagent.service.AgentTaskService;
import org.springframework.stereotype.Component;

/** 取消任务：委托持久化取消意图 + 收尾，不直接写状态。 */
@Component
public class CancelTaskOperation implements OperationExecutor {

    private final AgentTaskService taskService;

    public CancelTaskOperation(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public OperationResult execute(OperationContext context) {
        Long taskId = parseTaskId(context.targetId());
        if (taskId == null) {
            return OperationResult.failed("无效的任务 ID");
        }
        boolean requested = taskService.requestCancellation(taskId, "Cancelled by ops operator", "Ops cancellation");
        if (!requested) {
            return OperationResult.failed("任务不存在或已处于终态");
        }
        boolean finalized = taskService.finalizeCancellation(taskId, "Cancelled by ops operator", "Ops cancellation");
        return finalized ? OperationResult.ok("任务 " + taskId + " 已取消")
                : OperationResult.failed("任务取消未完成，请重试");
    }

    static Long parseTaskId(String targetId) {
        if (targetId == null || !targetId.matches("\\d{1,19}")) {
            return null;
        }
        try {
            return Long.parseLong(targetId);
        } catch (NumberFormatException failure) {
            return null;
        }
    }
}
