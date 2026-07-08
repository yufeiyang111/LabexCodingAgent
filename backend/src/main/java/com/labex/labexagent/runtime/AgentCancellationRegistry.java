package com.labex.labexagent.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AgentCancellationRegistry {
    private final Set<String> cancelledSessions = ConcurrentHashMap.newKeySet();

    public void cancel(String sessionId) {
        if (sessionId != null) {
            this.cancelledSessions.add(sessionId);
        }
    }

    public boolean isCancelled(String sessionId) {
        return sessionId != null && this.cancelledSessions.contains(sessionId);
    }

    public void reset(String sessionId) {
        if (sessionId != null) {
            this.cancelledSessions.remove(sessionId);
        }
    }
}

