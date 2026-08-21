package com.labex.monitor.operation;

import com.labex.labexagent.run.AgentRunRecoveryService;
import org.springframework.stereotype.Component;

/** 释放过期租约：触发权威恢复扫描（启动恢复同路径），幂等安全。 */
@Component
public class RecoverLeaseOperation implements OperationExecutor {

    private final AgentRunRecoveryService recoveryService;

    public RecoverLeaseOperation(AgentRunRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Override
    public OperationResult execute(OperationContext context) {
        recoveryService.recoverAfterStartup();
        return OperationResult.ok("已触发恢复扫描：过期租约与可恢复任务将按权威状态机处理");
    }
}
