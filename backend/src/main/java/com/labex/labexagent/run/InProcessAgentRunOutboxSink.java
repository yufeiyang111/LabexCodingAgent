package com.labex.labexagent.run;

import com.labex.entity.AgentRunOutbox;
import org.springframework.context.ApplicationEventPublisher;

public class InProcessAgentRunOutboxSink implements AgentRunOutboxSink {
    private final ApplicationEventPublisher eventPublisher;

    public InProcessAgentRunOutboxSink(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publish(AgentRunOutbox outbox) {
        this.eventPublisher.publishEvent(new AgentRunOutboxMessage(
                outbox.getOutboxId(),
                outbox.getEventId(),
                outbox.getTaskId(),
                outbox.getTopic(),
                outbox.getPayload()));
    }
}
