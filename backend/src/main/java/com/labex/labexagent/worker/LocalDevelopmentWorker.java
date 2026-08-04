package com.labex.labexagent.worker;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionObserver;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessCommandResolver;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.ProcessExecutor;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.terminal.TerminalSession;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Development-only implementation. Production must use an isolated worker implementation.
 */
@Component
@Profile("unsafe-local")
public class LocalDevelopmentWorker implements SandboxWorker {
    private final ProcessExecutor processExecutor;

    public LocalDevelopmentWorker(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    @Override
    public WorkspaceVersion prepare(WorkerRunSpec run) throws IOException {
        SecureWorkspacePath paths = workspacePaths(run);
        Files.createDirectories(paths.resolveForCreate(".labex-agent/worker-tmp"));
        return new WorkspaceVersion(run.runId(), paths.workspaceRoot());
    }

    @Override
    public ProcessExecutionResult execute(
            WorkerRunSpec run, ProcessExecutionRequest request, CancellationToken cancellationToken) {
        return execute(run, request, cancellationToken, ProcessExecutionObserver.none());
    }

    @Override
    public ProcessExecutionResult execute(
            WorkerRunSpec run, ProcessExecutionRequest request, CancellationToken cancellationToken,
            ProcessExecutionObserver observer) {
        try {
            prepare(run);
            requireWorkspacePath(run, request.workingDirectory());
            ProcessExecutionRequest safeRequest = new ProcessExecutionRequest(
                    request.command(),
                    request.workingDirectory(),
                    request.timeout(),
                    request.maxOutputChars(),
                    run.policy().safeEnvironment(run.workspaceRoot(), System.getenv()));
            return processExecutor.execute(
                        safeRequest, cancellationToken, chunk -> { },
                        identity -> observer.onStarted(identity.withWorkerContext("local", run.runId())));
        } catch (IOException | IllegalArgumentException e) {
            return new ProcessExecutionResult(
                    ExecutionStatus.INFRASTRUCTURE_ERROR, null, 0, e.getMessage(), false);
        }
    }

    @Override
    public WorkerProcess startProcess(WorkerRunSpec run, ProcessExecutionRequest request) throws IOException {
        prepare(run);
        requireWorkspacePath(run, request.workingDirectory());
        ProcessBuilder processBuilder = new ProcessBuilder(ProcessCommandResolver.resolve(request.command()))
                .directory(request.workingDirectory().toFile());
        processBuilder.environment().clear();
        processBuilder.environment().putAll(run.policy().safeEnvironment(run.workspaceRoot(), System.getenv()));
        return workerProcess(processBuilder.start(), () -> { });
    }

    @Override
    public InteractiveTerminal openTerminal(WorkerRunSpec run, TerminalSpec terminal) throws IOException {
        prepare(run);
        requireWorkspacePath(run, terminal.workingDirectory());
        TerminalSession session = new TerminalSession(
                terminal.sessionId(), terminal.workingDirectory().toString(), terminal.cols(), terminal.rows());
        session.setOutputCallback(terminal.outputListener()::accept);
        session.setCloseCallback(terminal.closeListener()::accept);
        session.start();
        return new InteractiveTerminal() {
            @Override
            public void writeInput(String data) {
                session.writeInput(data);
            }

            @Override
            public void resize(int cols, int rows) {
                session.resize(cols, rows);
            }

            @Override
            public void terminate() {
                session.destroy();
            }
        };
    }

    @Override
    public String readFile(WorkerRunSpec run, String relativePath) throws IOException {
        prepare(run);
        return Files.readString(resolveExistingRelativePath(run, relativePath), StandardCharsets.UTF_8);
    }

    @Override
    public void applyChange(WorkerRunSpec run, String relativePath, String content) throws IOException {
        prepare(run);
        Path target = resolveForCreateRelativePath(run, relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    @Override
    public List<ArtifactRef> collectArtifacts(WorkerRunSpec run, List<String> relativePaths) throws IOException {
        prepare(run);
        List<ArtifactRef> artifacts = new ArrayList<>();
        for (String relativePath : relativePaths == null ? List.<String>of() : relativePaths) {
            Path artifact;
            try {
                artifact = resolveExistingRelativePath(run, relativePath);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (Files.isRegularFile(artifact)) {
                artifacts.add(new ArtifactRef(toUnixRelativePath(run, artifact), Files.size(artifact)));
            }
        }
        return List.copyOf(artifacts);
    }

    @Override
    public void terminate(String runId) {
        // LocalProcessExecutor terminates each command tree when it times out or is cancelled.
    }

    protected Path resolveExistingRelativePath(WorkerRunSpec run, String relativePath) {
        return workspacePaths(run).resolveExisting(relativePath);
    }

    protected Path resolveForCreateRelativePath(WorkerRunSpec run, String relativePath) {
        return workspacePaths(run).resolveForCreate(relativePath);
    }

    protected void requireWorkspacePath(WorkerRunSpec run, Path path) {
        SecureWorkspacePath paths = workspacePaths(run);
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(paths.workspaceRoot())) {
            throw new IllegalArgumentException("path escapes worker workspace");
        }
        String relativePath = paths.workspaceRoot().relativize(normalized).toString();
        paths.resolveExisting(relativePath.isBlank() ? "." : relativePath);
    }

    private SecureWorkspacePath workspacePaths(WorkerRunSpec run) {
        return new SecureWorkspacePath(run.workspaceRoot());
    }

    protected WorkerProcess workerProcess(Process process, Runnable afterTerminate) {
        return new WorkerProcess() {
            @Override
            public java.io.InputStream standardOutput() {
                return process.getInputStream();
            }

            @Override
            public java.io.InputStream standardError() {
                return process.getErrorStream();
            }

            @Override
            public java.io.OutputStream standardInput() {
                return process.getOutputStream();
            }

            @Override
            public long processId() {
                return process.pid();
            }

            @Override
            public boolean isAlive() {
                return process.isAlive();
            }

            @Override
            public Integer exitCode() {
                try {
                    return process.exitValue();
                } catch (IllegalThreadStateException e) {
                    return null;
                }
            }

            @Override
            public void terminate() {
                try {
                    closeProcessStreams(process);
                    terminateProcessTree(process);
                } finally {
                    afterTerminate.run();
                }
            }
        };
    }


    private void closeProcessStreams(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    private void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
            try { process.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private String toUnixRelativePath(WorkerRunSpec run, Path path) {
        return run.workspaceRoot().relativize(path).toString().replace('\\', '/');
    }
}
