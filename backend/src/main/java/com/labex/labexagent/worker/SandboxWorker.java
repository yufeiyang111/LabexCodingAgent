package com.labex.labexagent.worker;

import com.labex.labexagent.execution.ProcessExecutionObserver;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.CancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Boundary between the trusted control plane and a workspace execution environment.
 */
public interface SandboxWorker {

    WorkspaceVersion prepare(WorkerRunSpec run) throws IOException;

    default ProcessExecutionResult execute(WorkerRunSpec run, ProcessExecutionRequest request) {
        return execute(run, request, CancellationToken.none());
    }

    ProcessExecutionResult execute(
            WorkerRunSpec run, ProcessExecutionRequest request, CancellationToken cancellationToken);

    default ProcessExecutionResult execute(
            WorkerRunSpec run, ProcessExecutionRequest request, CancellationToken cancellationToken,
            ProcessExecutionObserver observer) {
        return execute(run, request, cancellationToken);
    }

    WorkerProcess startProcess(WorkerRunSpec run, ProcessExecutionRequest request) throws IOException;

    default boolean usesLinuxShell() {
        return false;
    }

    /**
     * 返回当前 Worker 的实际 Shell 能力。WSL/Docker 覆盖为 Bash，unsafe-local 按宿主平台选择。
     * 该 descriptor 只描述执行环境，不承担权限或审批决策。
     */
    default WorkerShellDescriptor shellDescriptor(WorkerRunSpec run) {
        boolean networkEnabled = run != null && run.policy().networkEnabled();
        if (usesLinuxShell()) {
            return WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", networkEnabled);
        }
        String workspaceRoot = run == null ? "." : run.workspaceRoot().toString();
        return WorkerShellDescriptor.powerShell("windows", "powershell.exe", workspaceRoot, networkEnabled);
    }

    default String workspaceUri(WorkerRunSpec run, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(run.workspaceRoot())) {
            throw new IllegalArgumentException("path escapes worker workspace");
        }
        return normalized.toUri().toString();
    }

    default Path hostPath(WorkerRunSpec run, String workerUri) {
        Path path = Path.of(URI.create(workerUri)).toAbsolutePath().normalize();
        if (!path.startsWith(run.workspaceRoot())) {
            throw new IllegalArgumentException("worker URI escapes workspace");
        }
        return path;
    }

    InteractiveTerminal openTerminal(WorkerRunSpec run, TerminalSpec terminal) throws IOException;

    String readFile(WorkerRunSpec run, String relativePath) throws IOException;

    void applyChange(WorkerRunSpec run, String relativePath, String content) throws IOException;

    List<ArtifactRef> collectArtifacts(WorkerRunSpec run, List<String> relativePaths) throws IOException;

    void terminate(String runId);

    record WorkspaceVersion(String runId, Path workspaceRoot) {
    }

    record ArtifactRef(String relativePath, long sizeBytes) {
    }

    record TerminalSpec(
            String sessionId,
            Path workingDirectory,
            int cols,
            int rows,
            Consumer<String> outputListener,
            IntConsumer closeListener) {
        public TerminalSpec {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("terminal sessionId is required");
            }
            if (workingDirectory == null) {
                throw new IllegalArgumentException("terminal workingDirectory is required");
            }
            if (outputListener == null || closeListener == null) {
                throw new IllegalArgumentException("terminal listeners are required");
            }
        }
    }

    interface InteractiveTerminal {
        void writeInput(String data);

        void resize(int cols, int rows);

        void terminate();
    }

    interface WorkerProcess extends AutoCloseable {
        InputStream standardOutput();

        InputStream standardError();

        OutputStream standardInput();

        long processId();

        boolean isAlive();

        Integer exitCode();

        void terminate();

        @Override
        default void close() {
            terminate();
        }
    }
}
