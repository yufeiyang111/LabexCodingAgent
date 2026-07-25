package com.labex.labexagent.service;

import com.labex.entity.StudentProject;
import java.util.Collection;

/** Notifies context caches after workspace content has changed successfully. */
public interface WorkspaceContextInvalidator {
    void invalidate(StudentProject project, Collection<String> relativePaths);

    static WorkspaceContextInvalidator noop() {
        return (project, relativePaths) -> {
        };
    }
}
