package com.labex.labexagent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Keeps content-addressed, query-independent repo-map metadata in process memory. Ranking and
 * rendering remain the responsibility of {@link ProjectCodeMapService} so each request uses its
 * current query and priority paths.
 */
@Service
public class ProjectCodeMapMetadataCache {
    private static final int MAX_WORKSPACES = 8;
    private static final long INACTIVITY_MILLIS = 15 * 60 * 1_000L;

    private final Map<String, WorkspaceEntries> workspaces = new HashMap<>();

    synchronized List<ProjectCodeMapService.MappedFile> resolve(String workspaceKey,
                                                                 List<IncrementalContextService.ContextDocument> documents,
                                                                 Function<IncrementalContextService.ContextDocument,
                                                                         ProjectCodeMapService.MappedFile> parser) {
        long now = System.currentTimeMillis();
        evictInactive(now);
        WorkspaceEntries workspace = workspaces.computeIfAbsent(workspaceKey, ignored -> new WorkspaceEntries());
        workspace.lastAccessMillis = now;
        List<ProjectCodeMapService.MappedFile> mapped = new ArrayList<>();
        Set<String> currentPaths = new HashSet<>();
        for (IncrementalContextService.ContextDocument document : documents) {
            currentPaths.add(document.path());
            CachedMetadata cached = workspace.entries.get(document.path());
            if (cached != null && cached.hash.equals(document.hash())) {
                workspace.hits++;
                mapped.add(cached.mappedFile);
                continue;
            }
            workspace.misses++;
            ProjectCodeMapService.MappedFile parsed = parser.apply(document);
            workspace.entries.put(document.path(), new CachedMetadata(document.hash(), parsed));
            mapped.add(parsed);
        }
        workspace.entries.keySet().removeIf(path -> !currentPaths.contains(path));
        evictExcessWorkspaces();
        return mapped;
    }

    synchronized void invalidate(String workspaceKey) {
        workspaces.remove(workspaceKey);
    }

    synchronized CacheStats stats(String workspaceKey) {
        WorkspaceEntries workspace = workspaces.get(workspaceKey);
        if (workspace == null) return new CacheStats(0, 0, 0, 0);
        return new CacheStats(workspace.entries.size(), workspace.hits, workspace.misses, workspaces.size());
    }

    private void evictInactive(long now) {
        workspaces.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis >= INACTIVITY_MILLIS);
    }

    private void evictExcessWorkspaces() {
        while (workspaces.size() > MAX_WORKSPACES) {
            String oldest = workspaces.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastAccessMillis))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) return;
            workspaces.remove(oldest);
        }
    }

    record CacheStats(int entries, long hits, long misses, int workspaces) {
    }

    private static final class WorkspaceEntries {
        private final Map<String, CachedMetadata> entries = new HashMap<>();
        private long lastAccessMillis;
        private long hits;
        private long misses;
    }

    private record CachedMetadata(String hash, ProjectCodeMapService.MappedFile mappedFile) {
    }
}
