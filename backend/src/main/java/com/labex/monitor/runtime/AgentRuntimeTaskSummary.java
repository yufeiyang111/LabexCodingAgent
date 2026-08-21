package com.labex.monitor.runtime;

import java.time.LocalDateTime;

/** Agent 运行态任务列表项（只读投影，来自 AgentTask 权威状态）。 */
public record AgentRuntimeTaskSummary(
        Long taskId,
        String conversationId,
        String sessionId,
        String mode,
        String currentStatus,
        String currentStep,
        Long executionEpoch,
        String leaseOwner,
        LocalDateTime leaseExpireAt,
        LocalDateTime lastHeartbeatAt,
        Integer retryCount,
        Integer recoveryAttempts,
        LocalDateTime submittedAt,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        String summary,
        String provider,
        String modelName,
        String latestEvent,
        boolean overdue) {
}
