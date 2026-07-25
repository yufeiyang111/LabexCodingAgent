package com.labex.labexagent.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Bounds the in-process incremental document index by workspace count and inactivity. Content
 * reconciliation remains in {@link IncrementalContextService}; this cache only owns retention.
 */
@Component
class IncrementalContextCache {
    private static final int MAX_WORKSPACES = 8;
    private static final long INACTIVITY_MILLIS = 15 * 60 * 1_000L;

    private final Map<String, Entry> entries = new HashMap<>();

    synchronized Map<String, IncrementalContextService.ContextDocument> documents(String workspaceKey) {
        long now = System.currentTimeMillis();
        evictInactive(now);
        Entry entry = entries.get(workspaceKey);
        if (entry == null) return Map.of();
        entry.lastAccessMillis = now;
        return entry.documents;
    }

    synchronized void put(String workspaceKey, Map<String, IncrementalContextService.ContextDocument> documents) {
        long now = System.currentTimeMillis();
        evictInactive(now);
        entries.put(workspaceKey, new Entry(Map.copyOf(documents), now));
        evictExcessWorkspaces();
    }

    synchronized void invalidate(String workspaceKey) {
        entries.remove(workspaceKey);
    }

    synchronized CacheStats stats() {
        return new CacheStats(entries.size());
    }

    private void evictInactive(long now) {
        entries.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis >= INACTIVITY_MILLIS);
    }

    private void evictExcessWorkspaces() {
        while (entries.size() > MAX_WORKSPACES) {
            String oldest = entries.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastAccessMillis))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) return;
            entries.remove(oldest);
        }
    }

    record CacheStats(int workspaces) {
    }

    private static final class Entry {
        private final Map<String, IncrementalContextService.ContextDocument> documents;
        private long lastAccessMillis;

        private Entry(Map<String, IncrementalContextService.ContextDocument> documents, long lastAccessMillis) {
            this.documents = documents;
            this.lastAccessMillis = lastAccessMillis;
        }
    }
}
