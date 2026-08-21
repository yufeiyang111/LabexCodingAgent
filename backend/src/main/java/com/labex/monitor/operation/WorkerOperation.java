package com.labex.monitor.operation;

import org.springframework.stereotype.Component;

/** Worker 受控操作：记录管理意图（事件 + 审计），实际启停由部署层控制面负责。 */
@Component
public class WorkerOperation implements OperationExecutor {

    @Override
    public OperationResult execute(OperationContext context) {
        if (context.targetId() == null || context.targetId().isBlank()) {
            return OperationResult.failed("无效的 Worker ID");
        }
        return OperationResult.ok("Worker " + context.targetId() + " " + actionLabel(context.actionType())
                + " 请求已记录，实际生效取决于部署层控制面");
    }

    private String actionLabel(String actionType) {
        return actionType == null || !actionType.contains("RESUME") ? "暂停" : "恢复";
    }
}
