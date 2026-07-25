package com.labex.labexagent.workspace;

import com.labex.entity.StudentProject;
import java.nio.file.Path;

/**
 * Builds a validated workspace resolver from an owned project record.
 */
public final class ProjectWorkspace {
    private ProjectWorkspace() {
    }

    public static SecureWorkspacePath paths(StudentProject project) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            throw new IllegalArgumentException("Project workspace is unavailable");
        }
        return new SecureWorkspacePath(Path.of(project.getWorkspacePath()));
    }
}
