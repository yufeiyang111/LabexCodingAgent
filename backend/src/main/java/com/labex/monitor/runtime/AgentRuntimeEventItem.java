package com.labex.monitor.runtime;

import java.time.LocalDateTime;

/** Agent 运行态事件时间线项（来自 AgentRunEvent 权威可重放事件）。 */
public record AgentRuntimeEventItem(
        Long eventId,
        Long sequenceNumber,
        String eventType,
        String state,
        LocalDateTime createTime,
        String payload) {
}
