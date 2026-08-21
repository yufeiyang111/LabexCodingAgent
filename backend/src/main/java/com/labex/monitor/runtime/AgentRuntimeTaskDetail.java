package com.labex.monitor.runtime;

import java.time.LocalDateTime;

/** Agent 运行态任务详情（只读投影，来自 AgentTask / AgentRunEvent / AgentRunInteraction 权威状态）。 */
public record AgentRuntimeTaskDetail(
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
        Long runVersion,
        Long lastEventSequence,
        String latestEvent,
        String failureReason,
        String waitingReason,
        String waitingInteractionType,
        String waitingInteractionId) {
}
