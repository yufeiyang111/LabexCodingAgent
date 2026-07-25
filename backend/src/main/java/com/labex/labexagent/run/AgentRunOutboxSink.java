package com.labex.labexagent.run;

import com.labex.entity.AgentRunOutbox;

public interface AgentRunOutboxSink {
    void publish(AgentRunOutbox outbox) throws Exception;
}
