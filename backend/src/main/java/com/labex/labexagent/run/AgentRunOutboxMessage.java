package com.labex.labexagent.run;

public record AgentRunOutboxMessage(
        Long outboxId,
        Long eventId,
        Long taskId,
        String topic,
        String payload) {
}
