package com.labex.labexagent.service;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.nio.file.Path;
import java.util.Collection;
import org.springframework.stereotype.Service;

/** Coordinates invalidation of derived workspace context after a successful file mutation. */
@Service
public class WorkspaceContextCacheInvalidator implements WorkspaceContextInvalidator {
    private final IncrementalContextCache contextCache;
    private final ProjectCodeMapMetadataCache repoMapMetadataCache;

    public WorkspaceContextCacheInvalidator(IncrementalContextCache contextCache,
                                            ProjectCodeMapMetadataCache repoMapMetadataCache) {
        this.contextCache = contextCache;
        this.repoMapMetadataCache = repoMapMetadataCache;
    }

    @Override
    public void invalidate(StudentProject project, Collection<String> relativePaths) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            return;
        }
        Path root = new SecureWorkspacePath(Path.of(project.getWorkspacePath())).workspaceRoot();
        String workspaceKey = root.toAbsolutePath().normalize().toString();
        contextCache.invalidate(workspaceKey);
        repoMapMetadataCache.invalidate(workspaceKey);
    }
}
