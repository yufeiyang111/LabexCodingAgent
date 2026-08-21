package com.labex.monitor.operation;

import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.monitor.config.OpsAlertProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 重试任务：仅对可重试（重试退避）状态的任务生效，委托生命周期服务立即重试。 */
@Component
public class RetryTaskOperation implements OperationExecutor {

    private final AgentRunLifecycleService lifecycleService;
    private final AgentTaskService taskService;
    private final OpsAlertProperties properties;

    public RetryTaskOperation(AgentRunLifecycleService lifecycleService, AgentTaskService taskService,
                              OpsAlertProperties properties) {
        this.lifecycleService = lifecycleService;
        this.taskService = taskService;
        this.properties = properties;
    }

    @Override
    public OperationResult execute(OperationContext context) {
        Long taskId = CancelTaskOperation.parseTaskId(context.targetId());
        if (taskId == null) {
            return OperationResult.failed("无效的任务 ID");
        }
        boolean cancelled = lifecycleService.cancelScheduledRetry(taskId,
                Map.of("source", "ops", "reason", safeReason(context.reason())),
                "ops-retry-cancel-" + taskId);
        var schedule = taskService.scheduleModelRetry(taskId, properties.getTaskRetryMaxAttempts(), 0L,
                "Ops manual retry: " + safeReason(context.reason()));
        if (!cancelled || schedule == null) {
            return OperationResult.failed("任务不在可重试状态（仅支持重试退避中的任务）");
        }
        return OperationResult.ok("任务 " + taskId + " 已安排立即重试");
    }

    private String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "manual retry" : reason;
    }
}
