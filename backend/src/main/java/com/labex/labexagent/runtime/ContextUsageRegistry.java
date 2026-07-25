package com.labex.labexagent.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class ContextUsageRegistry {
    private final Map<String, ContextUsageSnapshot> snapshotsByConversation = new ConcurrentHashMap<>();

    public void save(ContextUsageSnapshot snapshot) {
        if (snapshot == null) return;
        Object conversationId = snapshot.toPayload().get("conversationId");
        if (conversationId instanceof String id && !id.isBlank()) snapshotsByConversation.put(id, snapshot);
    }

    public Optional<ContextUsageSnapshot> find(String conversationId) {
        return Optional.ofNullable(snapshotsByConversation.get(conversationId));
    }
}
