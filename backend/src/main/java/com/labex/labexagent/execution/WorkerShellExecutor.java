package com.labex.labexagent.execution;

import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Shared Worker Shell execution entry point.
 *
 * <p>It converts the complete Shell payload, workdir, timeout, and output limit
 * into one Worker request. Policy decisions stay in the control plane.</p>
 */
public final class WorkerShellExecutor {
    private final SandboxWorker worker;

    public WorkerShellExecutor(SandboxWorker worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public WorkerShellDescriptor descriptor(WorkerRunSpec run) {
        WorkerShellDescriptor descriptor = worker.shellDescriptor(run);
        if (descriptor != null) {
            return descriptor;
        }
        // Mockito and legacy Workers may not invoke the interface default method.
        boolean networkEnabled = run != null && run.policy().networkEnabled();
        if (worker.usesLinuxShell()) {
            return WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", networkEnabled);
        }
        String workspaceRoot = run == null ? "." : run.workspaceRoot().toString();
        return WorkerShellDescriptor.powerShell("windows", "powershell.exe", workspaceRoot, networkEnabled);
    }

    public PreparedExecution prepare(
            WorkerRunSpec run,
            String command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputChars,
            Path outputArtifactPath) {
        return prepare(descriptor(run), command, workingDirectory, timeout, maxOutputChars, outputArtifactPath);
    }

    public PreparedExecution prepare(
            WorkerShellDescriptor descriptor,
            String command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputChars,
            Path outputArtifactPath) {
        Objects.requireNonNull(descriptor, "descriptor");
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                ShellCommandFactory.create(descriptor, command),
                workingDirectory,
                timeout,
                maxOutputChars,
                outputArtifactPath);
        return new PreparedExecution(descriptor, request);
    }

    public ProcessExecutionResult execute(
            WorkerRunSpec run, PreparedExecution prepared, CancellationToken cancellationToken) {
        Objects.requireNonNull(prepared, "prepared");
        return worker.execute(run, prepared.request(), cancellationToken == null ? CancellationToken.none() : cancellationToken);
    }

    public SandboxWorker.WorkerProcess start(WorkerRunSpec run, PreparedExecution prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        return worker.startProcess(run, prepared.request());
    }

    public record PreparedExecution(WorkerShellDescriptor descriptor, ProcessExecutionRequest request) {
        public PreparedExecution {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(request, "request");
        }
    }
}
