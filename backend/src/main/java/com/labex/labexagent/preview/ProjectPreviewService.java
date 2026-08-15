package com.labex.labexagent.preview;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.labex.entity.AgentPreviewRun;
import com.labex.labexagent.commandsecurity.DirectCommandTokenizer;
import com.labex.labexagent.commandsecurity.StatefulCommandRedactor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.WorkerShellExecutor;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.mapper.AgentPreviewRunMapper;
import com.labex.labexagent.execution.ProcessHostIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 独立管理 Agent 预览服务的进程句柄、就绪探测和停止状态。
 * 普通 shell 始终保持有界；只有本服务创建的进程可跨工具调用持续运行。
 */
@Service
public class ProjectPreviewService {
    private final SandboxWorker sandboxWorker;
    private final AgentExecutionProperties executionProperties;
    private final PreviewRuntimeProperties properties;
    private final AgentPreviewRunMapper mapper;
    private final String processHostId;
    private final String ownerInstance;
    private final Map<String, LivePreview> liveRuns = new ConcurrentHashMap<>();
    private final Map<String, AgentPreviewRun> knownRuns = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    /** 面向真实 Worker 的无持久化构造器，仅用于独立进程契约测试。 */
    public ProjectPreviewService(SandboxWorker sandboxWorker, AgentExecutionProperties executionProperties,
                                 PreviewRuntimeProperties properties) {
        this(sandboxWorker, executionProperties, properties, null, "test-host", "test-instance");
    }

    @Autowired
    public ProjectPreviewService(SandboxWorker sandboxWorker, AgentExecutionProperties executionProperties,
                                 PreviewRuntimeProperties properties, AgentPreviewRunMapper mapper,
                                 ProcessHostIdentity hostIdentity,
                                 @Value("${labex-agent.instance-id:}") String configuredInstanceId) {
        this(sandboxWorker, executionProperties, properties, mapper,
                hostIdentity == null ? "host-unknown" : hostIdentity.hostId(),
                normalizedOwner(configuredInstanceId));
    }

    private ProjectPreviewService(SandboxWorker sandboxWorker, AgentExecutionProperties executionProperties,
                                  PreviewRuntimeProperties properties, AgentPreviewRunMapper mapper,
                                  String processHostId, String ownerInstance) {
        this.sandboxWorker = Objects.requireNonNull(sandboxWorker, "sandboxWorker");
        this.executionProperties = executionProperties == null ? new AgentExecutionProperties() : executionProperties;
        this.properties = properties == null ? new PreviewRuntimeProperties() : properties;
        this.mapper = mapper;
        this.processHostId = processHostId;
        this.ownerInstance = ownerInstance;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.properties.getConnectTimeoutMs()))
                .build();
    }

    @PostConstruct
    void markPreviousInstanceRunsUnavailable() {
        if (mapper == null) return;
        AgentPreviewRun replacement = new AgentPreviewRun();
        replacement.setStatus(Status.UNAVAILABLE_AFTER_RESTART.value());
        replacement.setFailureCode("owner_restarted");
        replacement.setStoppedAt(LocalDateTime.now());
        mapper.update(replacement, new LambdaUpdateWrapper<AgentPreviewRun>()
                .eq(AgentPreviewRun::getProcessHostId, processHostId)
                .in(AgentPreviewRun::getStatus, Status.STARTING.value(), Status.READY.value()));
    }

    public PreviewRun start(StartRequest request) {
        validate(request);
        if (!properties.isEnabled()) {
            return failedTransient(request, "preview_disabled");
        }
        if (!executionProperties.isNetworkDefaultEnabled()) {
            return failedTransient(request, "network_disabled");
        }
        AgentPreviewRun existing = findLiveByPort(request.studentId(), request.projectId(), request.port());
        if (existing != null) {
            return snapshot(existing);
        }

        String previewId = UUID.randomUUID().toString();
        Path workspace = request.workspaceRoot().toAbsolutePath().normalize();
        SecureWorkspacePath workspacePaths = new SecureWorkspacePath(workspace);
        Path workdir = resolveWorkdir(workspacePaths, request.workdir());
        String outputPath = ".labex-agent/artifacts/preview/" + previewId + ".log";
        Path outputArtifact = workspacePaths.resolveForCreate(outputPath);
        AgentPreviewRun run = newRun(previewId, request, relativeWorkdir(workspace, workdir), outputPath);
        persistNew(run);

        try {
            WorkerRunSpec workerRun = WorkerRunSpec.forWorkspace("preview-" + previewId, workspace, true);
            String command = resolveWorkerCompatibleCommand(workerRun, workdir, request.command());
            ProcessExecutionRequest processRequest;
            WorkerShellDescriptor descriptor;
            if (executionProperties.isSafeProfile()) {
                processRequest = new ProcessExecutionRequest(DirectCommandTokenizer.tokenize(command), workdir,
                        Duration.ofMillis(properties.getStartupTimeoutMs()), properties.getOutputMaxChars(), outputArtifact);
                descriptor = WorkerShellDescriptor.powerShell("direct", "direct", "/workspace", true);
            } else {
                WorkerShellExecutor shellExecutor = new WorkerShellExecutor(sandboxWorker);
                WorkerShellExecutor.PreparedExecution prepared = shellExecutor.prepare(workerRun, command, workdir,
                        Duration.ofMillis(properties.getStartupTimeoutMs()), properties.getOutputMaxChars(), outputArtifact);
                processRequest = prepared.request();
                descriptor = prepared.descriptor();
            }
            SandboxWorker.WorkerProcess process = sandboxWorker.startProcess(workerRun, processRequest);
            run.setProcessId(process.processId());
            run.setWorkerRuntime(descriptor.platform());
            persistUpdate(run);

            OutputLog outputLog = new OutputLog(outputArtifact, properties.getOutputMaxChars(), properties.getFailureHintMaxChars());
            LivePreview live = new LivePreview(run, process, outputLog);
            liveRuns.put(previewId, live);
            startOutputReader(live, process.standardOutput(), "stdout");
            startOutputReader(live, process.standardError(), "stderr");
            PreviewRun readiness = awaitReadiness(live, request.readinessPath());
            if (readiness.ready()) {
                startExitWatcher(live);
            }
            return readiness;
        } catch (Exception failure) {
            run.setStatus(Status.FAILED.value());
            run.setFailureCode("start_failed");
            run.setPublicUrl("");
            run.setStoppedAt(LocalDateTime.now());
            persistUpdate(run);
            return snapshot(run, "Preview process could not be started; inspect the output artifact.");
        }
    }

    private String resolveWorkerCompatibleCommand(WorkerRunSpec workerRun, Path workdir, String command) {
        if (!sandboxWorker.usesLinuxShell() || !isSimplePythonCommand(command)) {
            return command;
        }
        WorkerShellExecutor shellExecutor = new WorkerShellExecutor(sandboxWorker);
        WorkerShellExecutor.PreparedExecution probe = shellExecutor.prepare(workerRun,
                "if command -v python >/dev/null 2>&1; then exit 1; fi; command -v python3 >/dev/null 2>&1",
                workdir, Duration.ofMillis(properties.getStartupTimeoutMs()), properties.getOutputMaxChars(), null);
        ProcessExecutionResult result = shellExecutor.execute(workerRun, probe, CancellationToken.none());
        if (!result.succeeded()) {
            return command;
        }
        int offset = command.indexOf("python");
        return command.substring(0, offset) + "python3" + command.substring(offset + "python".length());
    }

    private boolean isSimplePythonCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            return "python".equals(DirectCommandTokenizer.tokenize(command).get(0));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public PreviewRun stop(Integer studentId, Integer projectId, String previewId) {
        AgentPreviewRun run = ownedRun(studentId, projectId, previewId);
        if (run == null) {
            throw new IllegalArgumentException("Preview run not found");
        }
        LivePreview live = liveRuns.remove(previewId);
        if (live == null) {
            if (Status.STARTING.value().equals(run.getStatus()) || Status.READY.value().equals(run.getStatus())) {
                run.setStatus(Status.UNAVAILABLE_AFTER_RESTART.value());
                run.setFailureCode("process_handle_unavailable");
                run.setPublicUrl("");
                run.setStoppedAt(LocalDateTime.now());
                persistUpdate(run);
            }
            return snapshot(run);
        }
        live.stopRequested = true;
        run.setStatus(Status.STOPPED.value());
        run.setFailureCode("");
        run.setPublicUrl("");
        run.setStoppedAt(LocalDateTime.now());
        persistUpdate(run);
        if (live.process.isAlive()) {
            live.process.terminate();
        }
        live.awaitOutputDrain(Duration.ofMillis(properties.getOutputDrainTimeoutMs()));
        live.outputLog.closeQuietly();
        return snapshot(run);
    }

    public PreviewRun status(Integer studentId, Integer projectId, String previewId) {
        AgentPreviewRun run = ownedRun(studentId, projectId, previewId);
        if (run == null) throw new IllegalArgumentException("Preview run not found");
        return snapshot(run);
    }

    public void stopAll() {
        for (LivePreview live : liveRuns.values().toArray(new LivePreview[0])) {
            try {
                stop(live.run.getStudentId(), live.run.getProjectId(), live.run.getPreviewId());
            } catch (RuntimeException ignored) {
                // 关闭阶段继续处理其余受控预览进程。
            }
        }
    }

    @PreDestroy
    void stopAllOnShutdown() {
        stopAll();
    }

    private PreviewRun awaitReadiness(LivePreview live, String readinessPath) {
        long deadline = System.nanoTime() + Duration.ofMillis(properties.getStartupTimeoutMs()).toNanos();
        String readinessUrl = readinessUrl(live.run.getPort(), readinessPath);
        while (System.nanoTime() < deadline) {
            if (!live.process.isAlive()) {
                return fail(live, "process_exited");
            }
            try {
                HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(URI.create(readinessUrl))
                                .timeout(Duration.ofMillis(properties.getConnectTimeoutMs())).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                live.run.setLastHttpStatus(response.statusCode());
                if (response.statusCode() >= 100 && response.statusCode() < 500) {
                    live.run.setStatus(Status.READY.value());
                    live.run.setFailureCode("");
                    live.run.setPublicUrl(publicUrl(live.run.getPort(), readinessPath));
                    live.run.setReadyAt(LocalDateTime.now());
                    persistUpdate(live.run);
                    return snapshot(live.run);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return fail(live, "readiness_interrupted");
            } catch (Exception ignored) {
                // 服务尚未监听，继续在受限窗口内探测。
            }
            sleep(properties.getPollIntervalMs());
        }
        return fail(live, "readiness_timeout");
    }

    private PreviewRun fail(LivePreview live, String failureCode) {
        liveRuns.remove(live.run.getPreviewId(), live);
        live.run.setStatus(Status.FAILED.value());
        live.run.setFailureCode(failureCode);
        live.run.setPublicUrl("");
        live.run.setStoppedAt(LocalDateTime.now());
        persistUpdate(live.run);
        if (live.process.isAlive()) live.process.terminate();
        live.awaitOutputDrain(Duration.ofMillis(properties.getOutputDrainTimeoutMs()));
        live.outputLog.closeQuietly();
        return snapshot(live.run, failureHint(live, failureCode));
    }

    private String failureHint(LivePreview live, String failureCode) {
        String captured = live.outputLog.failureHint();
        if (!captured.isBlank()) {
            return captured;
        }
        String exitCode = live.process.exitCode() == null ? "unknown" : String.valueOf(live.process.exitCode());
        return "No stdout/stderr was captured before " + failureCode
                + " (exit_code=" + exitCode + "); inspect output artifact: " + live.run.getOutputPath();
    }

    private void startOutputReader(LivePreview live, InputStream stream, String source) {
        Thread reader = new Thread(() -> {
            StatefulCommandRedactor redactor = new StatefulCommandRedactor();
            try (InputStreamReader input = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    String redacted = redactor.append(new String(buffer, 0, length));
                    if (!redacted.isEmpty()) live.outputLog.append(source, redacted);
                }
                String tail = redactor.finish();
                if (!tail.isEmpty()) live.outputLog.append(source, tail);
            } catch (IOException ignored) {
                // 终止进程会关闭流；最终状态由 watcher 统一写入。
            } finally {
                live.outputReaderFinished();
            }
        }, "labex-preview-log-" + live.run.getPreviewId() + "-" + source);
        reader.setDaemon(true);
        reader.start();
    }

    private void startExitWatcher(LivePreview live) {
        Thread watcher = new Thread(() -> {
            while (live.process.isAlive()) {
                sleep(50);
            }
            liveRuns.remove(live.run.getPreviewId(), live);
            if (live.stopRequested || Status.STOPPED.value().equals(live.run.getStatus())) {
                return;
            }
            if (Status.READY.value().equals(live.run.getStatus())) {
                live.run.setStatus(Status.EXITED.value());
                live.run.setFailureCode("process_exited");
            } else if (Status.STARTING.value().equals(live.run.getStatus())) {
                live.run.setStatus(Status.FAILED.value());
                live.run.setFailureCode("process_exited");
            }
            live.run.setPublicUrl("");
            live.run.setStoppedAt(LocalDateTime.now());
            persistUpdate(live.run);
            live.awaitOutputDrain(Duration.ofMillis(properties.getOutputDrainTimeoutMs()));
            live.outputLog.closeQuietly();
        }, "labex-preview-exit-" + live.run.getPreviewId());
        watcher.setDaemon(true);
        watcher.start();
    }

    private AgentPreviewRun newRun(String previewId, StartRequest request, String workdir, String outputPath) {
        AgentPreviewRun run = new AgentPreviewRun();
        run.setPreviewId(previewId);
        run.setStudentId(request.studentId());
        run.setProjectId(request.projectId());
        run.setTaskId(request.taskId());
        run.setStatus(Status.STARTING.value());
        run.setWorkdir(workdir);
        run.setPort(request.port());
        run.setPublicUrl("");
        run.setProcessHostId(processHostId);
        run.setOwnerInstance(ownerInstance);
        run.setOutputPath(outputPath);
        run.setStartedAt(LocalDateTime.now());
        return run;
    }

    private PreviewRun failedTransient(StartRequest request, String failureCode) {
        return new PreviewRun("", Status.FAILED, false, "", null, failureCode, "", null, "");
    }

    private AgentPreviewRun findLiveByPort(Integer studentId, Integer projectId, int port) {
        return liveRuns.values().stream()
                .map(item -> item.run)
                .filter(run -> Objects.equals(studentId, run.getStudentId())
                        && Objects.equals(projectId, run.getProjectId())
                        && Objects.equals(port, run.getPort()))
                .filter(run -> Status.STARTING.value().equals(run.getStatus()) || Status.READY.value().equals(run.getStatus()))
                .findFirst().orElse(null);
    }

    private AgentPreviewRun ownedRun(Integer studentId, Integer projectId, String previewId) {
        if (previewId == null || previewId.isBlank()) return null;
        AgentPreviewRun run = knownRuns.get(previewId);
        if (run == null && mapper != null) run = mapper.selectById(previewId);
        if (run == null || !Objects.equals(studentId, run.getStudentId()) || !Objects.equals(projectId, run.getProjectId())) {
            return null;
        }
        knownRuns.put(run.getPreviewId(), run);
        return run;
    }

    private void persistNew(AgentPreviewRun run) {
        knownRuns.put(run.getPreviewId(), run);
        if (mapper != null) mapper.insert(run);
    }

    private void persistUpdate(AgentPreviewRun run) {
        knownRuns.put(run.getPreviewId(), run);
        if (mapper != null) mapper.updateById(run);
    }

    private Path resolveWorkdir(SecureWorkspacePath workspacePaths, String requested) {
        String relative = requested == null || requested.isBlank() ? "." : requested.trim();
        return workspacePaths.resolveExisting(relative);
    }

    private String relativeWorkdir(Path workspace, Path workdir) {
        String relative = workspace.relativize(workdir).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private String readinessUrl(int port, String path) {
        return "http://" + properties.getReadinessHost() + ":" + port + normalizePath(path);
    }

    private String publicUrl(int port, String path) {
        return "http://" + properties.getPublicHost() + ":" + port + normalizePath(path);
    }

    private String normalizePath(String value) {
        String path = value == null || value.isBlank() ? "/" : value.trim();
        if (!path.startsWith("/")) path = "/" + path;
        if (path.contains("\r") || path.contains("\n") || path.contains("://")) {
            throw new IllegalArgumentException("readiness_path is invalid");
        }
        return path;
    }

    private void validate(StartRequest request) {
        if (request == null || request.studentId() == null || request.projectId() == null
                || request.workspaceRoot() == null || request.command() == null || request.command().isBlank()) {
            throw new IllegalArgumentException("preview request is incomplete");
        }
        if (request.port() < properties.getMinPort() || request.port() > properties.getMaxPort()) {
            throw new IllegalArgumentException("preview port is outside the configured range");
        }
        normalizePath(request.readinessPath());
    }

    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String normalizedOwner(String configuredInstanceId) {
        String base = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? "labex-preview" : configuredInstanceId.trim();
        return base + ":" + UUID.randomUUID();
    }

    public enum Status {
        STARTING("starting"), READY("ready"), FAILED("failed"), EXITED("exited"), STOPPED("stopped"),
        UNAVAILABLE_AFTER_RESTART("unavailable_after_restart");
        private final String value;
        Status(String value) { this.value = value; }
        String value() { return value; }
        static Status from(String value) {
            for (Status status : values()) if (status.value.equals(value)) return status;
            return FAILED;
        }
    }

    public record StartRequest(Integer studentId, Integer projectId, Long taskId, Path workspaceRoot,
                               String workdir, String command, int port, String readinessPath) {
    }

    public record PreviewRun(String previewId, Status status, boolean ready, String publicUrl, Long processId,
                             String failureCode, String outputPath, Integer lastHttpStatus, String failureHint) {
    }

    private PreviewRun snapshot(AgentPreviewRun run) {
        return snapshot(run, "");
    }

    private PreviewRun snapshot(AgentPreviewRun run, String failureHint) {
        Status status = Status.from(run.getStatus());
        return new PreviewRun(run.getPreviewId(), status, status == Status.READY,
                status == Status.READY ? nullToEmpty(run.getPublicUrl()) : "", run.getProcessId(),
                nullToEmpty(run.getFailureCode()), nullToEmpty(run.getOutputPath()), run.getLastHttpStatus(),
                status == Status.READY ? "" : nullToEmpty(failureHint));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class LivePreview {
        private final AgentPreviewRun run;
        private final SandboxWorker.WorkerProcess process;
        private final OutputLog outputLog;
        private final CountDownLatch outputReaders = new CountDownLatch(2);
        private volatile boolean stopRequested;
        private LivePreview(AgentPreviewRun run, SandboxWorker.WorkerProcess process, OutputLog outputLog) {
            this.run = run; this.process = process; this.outputLog = outputLog;
        }

        private void outputReaderFinished() {
            outputReaders.countDown();
        }

        private void awaitOutputDrain(Duration timeout) {
            try {
                outputReaders.await(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class OutputLog {
        private final BufferedWriter writer;
        private final int outputMaxChars;
        private final int failureHintMaxChars;
        private final StringBuilder failureTail = new StringBuilder();
        private int capturedChars;
        private boolean truncated;

        private OutputLog(Path path, int outputMaxChars, int failureHintMaxChars) throws IOException {
            Files.createDirectories(path.getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            this.outputMaxChars = outputMaxChars;
            this.failureHintMaxChars = failureHintMaxChars;
        }

        private synchronized void append(String source, String value) {
            appendFailureTail(source, value);
            try {
                int remaining = outputMaxChars - capturedChars;
                if (remaining <= 0) {
                    writeTruncationMarker();
                    return;
                }
                String captured = value.length() <= remaining ? value : value.substring(0, remaining);
                writer.write("[" + source + "] ");
                writer.write(captured);
                if (!captured.endsWith("\n")) writer.newLine();
                capturedChars += captured.length();
                if (captured.length() < value.length()) writeTruncationMarker();
                writer.flush();
            } catch (IOException ignored) {
                // Logging failure never fabricates preview readiness; only real HTTP readiness can do that.
            }
        }

        private void appendFailureTail(String source, String value) {
            failureTail.append('[').append(source).append("] ").append(value);
            int excess = failureTail.length() - failureHintMaxChars;
            if (excess > 0) failureTail.delete(0, excess);
        }

        private synchronized String failureHint() {
            return failureTail.toString().replace('\r', ' ').replace('\n', ' ').trim();
        }

        private void writeTruncationMarker() throws IOException {
            if (truncated) return;
            truncated = true;
            writer.write("[preview output truncated]");
            writer.newLine();
            writer.flush();
        }

        private synchronized void closeQuietly() {
            try { writer.close(); } catch (IOException ignored) { }
        }
    }

}