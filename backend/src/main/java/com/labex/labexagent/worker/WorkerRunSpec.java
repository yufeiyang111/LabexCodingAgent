package com.labex.labexagent.worker;

import java.nio.file.Path;
import java.util.Objects;

public record WorkerRunSpec(String runId, Path workspaceRoot, WorkerPolicy policy) {

    public WorkerRunSpec {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(policy, "policy");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public static WorkerRunSpec forWorkspace(String runId, Path workspaceRoot) {
        return new WorkerRunSpec(runId, workspaceRoot, WorkerPolicy.defaults());
    }
}
