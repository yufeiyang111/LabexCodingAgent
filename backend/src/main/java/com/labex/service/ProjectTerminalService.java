package com.labex.service;

import com.labex.entity.StudentProject;
import com.labex.labexagent.commandsecurity.CommandRedactor;
import com.labex.labexagent.commandsecurity.StatefulCommandRedactor;
import com.labex.labexagent.commandsecurity.DirectCommandTokenizer;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.WorkerShellExecutor;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProjectTerminalService {
    private static final Logger log = LoggerFactory.getLogger(ProjectTerminalService.class);
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final SandboxWorker sandboxWorker;
    private final AgentExecutionProperties executionProperties;
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    /** Compatibility constructor: managed terminal uses the OpenCode-first Shell profile. */
    public ProjectTerminalService(SandboxWorker sandboxWorker) {
        this(sandboxWorker, new AgentExecutionProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectTerminalService(SandboxWorker sandboxWorker, AgentExecutionProperties executionProperties) {
        this.sandboxWorker = sandboxWorker;
        this.executionProperties = executionProperties == null ? new AgentExecutionProperties() : executionProperties;
    }

    public List<TerminalSession> list(Integer studentId, Integer projectId) {
        return this.sessions.values().stream()
            .filter(item -> item.studentId.equals(studentId) && item.projectId.equals(projectId))
            .sorted((a, b) -> a.createdAt.compareTo(b.createdAt))
            .toList();
    }

    public TerminalSession create(Integer studentId, StudentProject project, String name, String path) throws Exception {
        Path cwd = this.resolveCwd(project, path);
        TerminalSession session = new TerminalSession();
        session.sessionId = UUID.randomUUID().toString();
        session.studentId = studentId;
        session.projectId = project.getProjectId();
        session.name = (name == null || name.isBlank()) ? ("Terminal " + (this.list(studentId, project.getProjectId()).size() + 1)) : name;
        session.cwd = cwd.toString();
        session.createdAt = LocalDateTime.now();
        session.append("[Terminal] " + session.name + "\n[WorkDir] " + session.cwd + "\n");
        this.sessions.put(session.sessionId, session);
        return session;
    }

    public TerminalSession getOwned(Integer studentId, Integer projectId, String sessionId) {
        TerminalSession session = this.sessions.get(sessionId);
        if (session == null || !session.studentId.equals(studentId) || !session.projectId.equals(projectId)) {
            return null;
        }
        return session;
    }

    public TerminalRunResult run(TerminalSession session, StudentProject project, String command, String path, boolean longRunning, int timeoutSeconds) throws Exception {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        if (session.process != null && session.process.isAlive()) {
            throw new IllegalStateException("Another command is already running");
        }
        Path cwd = path == null || path.isBlank()
                ? this.resolveSavedCwd(project, session.cwd)
                : this.resolveCwd(project, path);
        session.cwd = cwd.toString();
        session.lastCommand = command;
        session.exitCode = null;
        session.running = true;
        session.executionStartedAtMillis = System.currentTimeMillis();
        session.outputCharCount = 0L;
        session.append("\n$ " + command + "\n");
        int timeout = Math.min(600, Math.max(1, timeoutSeconds));
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("terminal-" + session.sessionId,
                workspacePaths(project).workspaceRoot(), executionProperties.isNetworkDefaultEnabled());
        ProcessExecutionRequest request;
        WorkerShellExecutor shellExecutor = null;
        WorkerShellExecutor.PreparedExecution prepared = null;
        String shell;
        if (executionProperties.isSafeProfile()) {
            request = new ProcessExecutionRequest(
                    DirectCommandTokenizer.tokenize(command), cwd, Duration.ofSeconds(timeout), MAX_OUTPUT_CHARS);
            shell = "direct";
        } else {
            shellExecutor = new WorkerShellExecutor(sandboxWorker);
            prepared = shellExecutor.prepare(run, command, cwd, Duration.ofSeconds(timeout), MAX_OUTPUT_CHARS, null);
            request = prepared.request();
            shell = prepared.descriptor().shellName();
        }
        String workdir = relativeWorkingDirectory(project, cwd);
        if (!longRunning) {
            ProcessExecutionResult result = shellExecutor == null
                    ? sandboxWorker.execute(run, request, CancellationToken.none())
                    : shellExecutor.execute(run, prepared, CancellationToken.none());
            session.running = false;
            session.exitCode = result.exitCode();
            session.lastExecution = TerminalExecution.from(result, shell, workdir, timeout);
            if (!result.output().isBlank()) {
                session.append(result.output());
            }
            return new TerminalRunResult(false, session.exitCode, session.snapshot(), session, session.lastExecution);
        }
        SandboxWorker.WorkerProcess process = shellExecutor == null
                ? sandboxWorker.startProcess(run, request)
                : shellExecutor.start(run, prepared);
        session.lastExecution = TerminalExecution.running(shell, workdir, timeout);
        session.process = process;
        this.startOutputReader(session, process);
        if (longRunning) {
            return new TerminalRunResult(true, null, session.snapshot(), session);
        }
        throw new IllegalStateException("unreachable");
    }

    public void stop(TerminalSession session) {
        boolean wasRunning = session.running || (session.process != null && session.process.isAlive());
        if (!wasRunning) {
            return;
        }
        // 先把会话标记为 cancelled，再终止进程；输出 reader 的收敛逻辑只会覆盖 running 状态。
        session.running = false;
        session.exitCode = -1;
        if (session.lastExecution != null && "running".equals(session.lastExecution.status())) {
            session.lastExecution = new TerminalExecution("cancelled", session.lastExecution.shell(),
                    session.lastExecution.workdir(), -1, currentExecutionDurationMs(session), false,
                    session.outputCharCount, session.lastExecution.timeoutSeconds());
        }
        session.append("\n[Stopped]\n");
        if (session.process != null && session.process.isAlive()) {
            session.process.terminate();
        }
    }

    public void remove(TerminalSession session) {
        this.stop(session);
        this.sessions.remove(session.sessionId);
    }

    private void startOutputReader(TerminalSession session, SandboxWorker.WorkerProcess process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.standardOutput(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    session.appendOutputChunk(line + "\n");
                }
            } catch (Exception e) {
                session.append("\n[terminal reader error] " + e.getMessage() + "\n");
            } finally {
                session.finishOutputRedaction();
                if (!process.isAlive()) {
                    session.running = false;
                    Integer exitCode = process.exitCode();
                    if (exitCode != null) {
                        session.exitCode = exitCode;
                        if (session.lastExecution != null && "running".equals(session.lastExecution.status())) {
                            session.lastExecution = new TerminalExecution(
                                    Integer.valueOf(0).equals(exitCode) ? "succeeded" : "failed",
                                    session.lastExecution.shell(), session.lastExecution.workdir(), exitCode,
                                    currentExecutionDurationMs(session), false, session.outputCharCount,
                                    session.lastExecution.timeoutSeconds());
                        }
                        session.append("\nexit=" + session.exitCode + "\n");
                    }
                }
            }
        }, "project-terminal-reader-" + session.sessionId);
        thread.setDaemon(true);
        thread.start();
    }

    private long currentExecutionDurationMs(TerminalSession session) {
        long startedAt = session.executionStartedAtMillis;
        if (startedAt <= 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private Path resolveCwd(StudentProject project, String path) {
        SecureWorkspacePath paths = workspacePaths(project);
        if (path == null || path.isBlank()) {
            return paths.workspaceRoot();
        }
        String normalized = path.trim().replace('\\', '/');
        try {
            Path requested = Path.of(normalized);
            if (requested.isAbsolute() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException("Unsafe path");
            }
            Path candidate = paths.resolveExisting(normalized);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate.getParent();
            }
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Unsafe path");
            }
            return candidate;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Unsafe path", e);
        } catch (IllegalArgumentException e) {
            if ("Unsafe path".equals(e.getMessage())) {
                throw e;
            }
            throw new IllegalArgumentException("Unsafe path", e);
        }
    }

    private Path resolveSavedCwd(StudentProject project, String savedCwd) {
        SecureWorkspacePath paths = workspacePaths(project);
        if (savedCwd == null || savedCwd.isBlank()) {
            return paths.workspaceRoot();
        }
        try {
            Path candidate = Path.of(savedCwd).toAbsolutePath().normalize();
            if (!candidate.startsWith(paths.workspaceRoot())) {
                throw new IllegalArgumentException("Unsafe path");
            }
            String relativePath = paths.workspaceRoot().relativize(candidate).toString();
            Path resolved = paths.resolveExisting(relativePath.isBlank() ? "." : relativePath);
            if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Unsafe path");
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Unsafe path", e);
        }
    }

    private String relativeWorkingDirectory(StudentProject project, Path workingDirectory) {
        Path root = workspacePaths(project).workspaceRoot();
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return ".";
        }
        String relative = root.relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private SecureWorkspacePath workspacePaths(StudentProject project) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            throw new IllegalArgumentException("Project workspace is unavailable");
        }
        return new SecureWorkspacePath(Path.of(project.getWorkspacePath()));
    }

    public static class TerminalSession {
        public String sessionId;
        public Integer studentId;
        public Integer projectId;
        public String name;
        public String cwd;
        public String lastCommand;
        public volatile Integer exitCode;
        public volatile boolean running;
        public LocalDateTime createdAt;
        public SandboxWorker.WorkerProcess process;
        public volatile TerminalExecution lastExecution;
        public long executionStartedAtMillis;
        public volatile long outputCharCount;
        public List<String> output = new java.util.ArrayList<>();
        private final StatefulCommandRedactor outputRedactor = new StatefulCommandRedactor();

        public synchronized void append(String line) {
            output.add(CommandRedactor.redact(line));
        }

        public synchronized void appendOutputChunk(String chunk) {
            outputCharCount += chunk.length();
            String redacted = outputRedactor.append(chunk);
            if (!redacted.isEmpty()) {
                output.add(redacted);
            }
        }

        public synchronized void finishOutputRedaction() {
            String redacted = outputRedactor.finish();
            if (!redacted.isEmpty()) {
                output.add(redacted);
            }
        }

        public synchronized String snapshot() {
            return CommandRedactor.redact(String.join("\n", output) + outputRedactor.snapshotSuffix());
        }
    }

    public record TerminalExecution(
            String status,
            String shell,
            String workdir,
            Integer exitCode,
            long durationMs,
            boolean truncated,
            long outputChars,
            int timeoutSeconds) {
        static TerminalExecution from(ProcessExecutionResult result, String shell, String workdir, int timeoutSeconds) {
            return new TerminalExecution(result.status().name().toLowerCase(java.util.Locale.ROOT), shell, workdir,
                    result.exitCode(), result.durationMs(), result.truncated(), result.outputChars(), timeoutSeconds);
        }

        static TerminalExecution running(String shell, String workdir, int timeoutSeconds) {
            return new TerminalExecution("running", shell, workdir, null, 0L, false, 0L, timeoutSeconds);
        }
    }

    public static class TerminalRunResult {
        private final boolean running;
        private final Integer exitCode;
        private final String output;
        private final TerminalSession session;
        private final TerminalExecution execution;
        TerminalRunResult(boolean running, Integer exitCode, String output) {
            this(running, exitCode, output, null, null);
        }
        TerminalRunResult(boolean running, Integer exitCode, String output, TerminalSession session) {
            this(running, exitCode, output, session, session == null ? null : session.lastExecution);
        }
        TerminalRunResult(boolean running, Integer exitCode, String output, TerminalSession session,
                          TerminalExecution execution) {
            this.running = running;
            this.exitCode = exitCode;
            this.output = output;
            this.session = session;
            this.execution = execution;
        }
        public boolean running() { return running; }
        public Integer exitCode() { return exitCode; }
        public String output() { return output; }
        public TerminalSession session() { return session; }
        public TerminalExecution execution() { return execution; }
    }
}
