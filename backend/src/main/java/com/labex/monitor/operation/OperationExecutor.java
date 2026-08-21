package com.labex.monitor.operation;

/** 受控操作执行接口：返回执行结果与安全消息，失败信息不得携带内部细节。 */
public interface OperationExecutor {

    OperationResult execute(OperationContext context);

    record OperationContext(String actionType, String targetType, String targetId, String reason) {
    }

    record OperationResult(boolean succeeded, String message) {
        public static OperationResult ok(String message) {
            return new OperationResult(true, message);
        }

        public static OperationResult failed(String message) {
            return new OperationResult(false, message);
        }
    }
}