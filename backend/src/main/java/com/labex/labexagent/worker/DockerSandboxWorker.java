package com.labex.labexagent.worker;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionObserver;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.ProcessExecutor;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.terminal.TerminalSession;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * OCI implementation using the local Docker CLI. Network is disabled by default and can only be enabled on a one-shot approved worker run.
 */
@Component
@Profile({"docker", "prod", "production"})
public class DockerSandboxWorker extends LocalDevelopmentWorker {
    private static final String LOCAL_SMOKE_IMAGE = "labex-agent-sandbox:smoke";
    private static final String CONTAINER_RUNTIME_ROOT = "/workspace/.labex-agent/runtime";
    private final AtomicLong invocationCounter = new AtomicLong();
    private final Set<String> activeContainers = ConcurrentHashMap.newKeySet();
    private final String configuredImage;
    private final Environment environment;
    private final ProcessExecutor processExecutor;
    private final java.util.concurrent.Semaphore containerSlots;
    private final boolean wslWorkspaceMapping;

    @Autowired
    public DockerSandboxWorker(
            ProcessExecutor processExecutor,
            @Value("${labex-agent.worker.docker.image:${LABEX_AGENT_WORKER_DOCKER_IMAGE:}}") String configuredImage,
            @Value("${labex-agent.worker.docker.max-concurrent-containers:8}") int maxConcurrentContainers,
            @Value("${labex-agent.worker.docker.wsl-workspace-mapping:false}") boolean wslWorkspaceMapping,
            Environment environment) {
        super(processExecutor);
        this.processExecutor = processExecutor;
        this.configuredImage = configuredImage == null ? "" : configuredImage.trim();
        this.environment = environment;
        this.containerSlots = new java.util.concurrent.Semaphore(Math.max(1, maxConcurrentContainers));
        this.wslWorkspaceMapping = wslWorkspaceMapping;
    }

    public DockerSandboxWorker(ProcessExecutor processExecutor) {
        super(processExecutor);
        this.processExecutor = processExecutor;
        this.configuredImage = "";
        this.environment = null;
        this.containerSlots = new java.util.concurrent.Semaphore(8);
        this.wslWorkspaceMapping = false;
    }

    /** 兼容测试与旧调用方的三参构造器；Spring 通过五参 @Autowired 构造器注入。 */
    public DockerSandboxWorker(ProcessExecutor processExecutor, String configuredImage, Environment environment) {
        this(processExecutor, configuredImage, 8, false, environment);
    }

    @PostConstruct
    void requireImageInProduction() {
        if (environment != null
                && (environment.matchesProfiles("prod") || environment.matchesProfiles("production"))
                && (configuredImage.isBlank() || LOCAL_SMOKE_IMAGE.equals(configuredImage))) {
            throw new IllegalStateException(
                    "Production requires a non-smoke LABEX_AGENT_WORKER_DOCKER_IMAGE for an isolated sandbox worker");
        }
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
        if (!containerSlots.tryAcquire()) {
            return new ProcessExecutionResult(
                    ExecutionStatus.INFRASTRUCTURE_ERROR, null, 0,
                    "docker worker concurrency limit reached; retry after active containers finish", false);
        }
        try {
            return executeWithContainerSlot(run, request, cancellationToken, observer);
        } finally {
            containerSlots.release();
        }
    }

    private ProcessExecutionResult executeWithContainerSlot(
            WorkerRunSpec run, ProcessExecutionRequest request, CancellationToken cancellationToken,
            ProcessExecutionObserver observer) {
        try {
            prepare(run);
            requireWorkspacePath(run, request.workingDirectory());
            String containerName = containerName(run.runId());
            activeContainers.add(containerName);
            try {
                ProcessExecutionRequest dockerRequest = new ProcessExecutionRequest(
                        buildDockerCommand(run, request, containerName),
                        run.workspaceRoot(),
                        request.timeout(),
                        request.maxOutputChars(),
                        run.policy().safeEnvironment(run.workspaceRoot(), System.getenv()),
                        request.outputArtifactPath());
                return processExecutor.execute(
                        dockerRequest, cancellationToken, chunk -> { },
                        identity -> observer.onStarted(identity.withWorkerContext("docker", run.runId())));
            } finally {
                removeContainer(containerName);
                activeContainers.remove(containerName);
            }
        } catch (IOException | IllegalArgumentException e) {
            return new ProcessExecutionResult(
                    ExecutionStatus.INFRASTRUCTURE_ERROR, null, 0, e.getMessage(), false);
        }
    }

    @Override
    public WorkerProcess startProcess(WorkerRunSpec run, ProcessExecutionRequest request) throws IOException {
        prepare(run);
        requireWorkspacePath(run, request.workingDirectory());
        String containerName = containerName(run.runId());
        activeContainers.add(containerName);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(buildDockerProcessCommand(run, request, containerName))
                    .directory(run.workspaceRoot().toFile());
            processBuilder.environment().clear();
            processBuilder.environment().putAll(run.policy().safeEnvironment(run.workspaceRoot(), System.getenv()));
            return workerProcess(processBuilder.start(), () -> {
                activeContainers.remove(containerName);
                removeContainer(containerName);
            });
        } catch (IOException e) {
            activeContainers.remove(containerName);
            removeContainer(containerName);
            throw e;
        }
    }

    @Override
    public boolean usesLinuxShell() {
        return true;
    }

    @Override
    public WorkerShellDescriptor shellDescriptor(WorkerRunSpec run) {
        return WorkerShellDescriptor.bash(
                "linux-docker", "/bin/bash", "/workspace", run != null && run.policy().networkEnabled());
    }

    @Override
    public String workspaceUri(WorkerRunSpec run, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        requireWorkspacePath(run, normalized);
        Path relative = run.workspaceRoot().relativize(normalized);
        String suffix = relative.toString().replace('\\', '/');
        return suffix.isBlank() ? "file:///workspace" : "file:///workspace/" + suffix;
    }

    @Override
    public Path hostPath(WorkerRunSpec run, String workerUri) {
        try {
            String containerPath = java.net.URI.create(workerUri).getPath();
            if (containerPath == null || !containerPath.equals("/workspace") && !containerPath.startsWith("/workspace/")) {
                throw new IllegalArgumentException("worker URI escapes workspace");
            }
            String relative = containerPath.length() == "/workspace".length()
                    ? ""
                    : containerPath.substring("/workspace/".length());
            Path resolved = run.workspaceRoot().resolve(relative).normalize();
            requireWorkspacePath(run, resolved);
            return resolved;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("worker URI is invalid", e);
        }
    }

    @Override
    public InteractiveTerminal openTerminal(WorkerRunSpec run, TerminalSpec terminal) throws IOException {
        prepare(run);
        requireWorkspacePath(run, terminal.workingDirectory());
        String containerName = containerName(run.runId());
        activeContainers.add(containerName);
        TerminalSession session = new TerminalSession(
                terminal.sessionId(),
                run.workspaceRoot().toString(),
                terminal.cols(),
                terminal.rows(),
                buildDockerTerminalCommand(run, terminal, containerName),
                run.policy().safeEnvironment(run.workspaceRoot(), System.getenv()));
        session.setOutputCallback(terminal.outputListener()::accept);
        session.setCloseCallback(exitCode -> {
            activeContainers.remove(containerName);
            removeContainer(containerName);
            terminal.closeListener().accept(exitCode);
        });
        try {
            session.start();
        } catch (IOException e) {
            activeContainers.remove(containerName);
            removeContainer(containerName);
            throw e;
        }
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
                activeContainers.remove(containerName);
                removeContainer(containerName);
            }
        };
    }

    List<String> buildDockerCommand(WorkerRunSpec run, ProcessExecutionRequest request) {
        return buildDockerCommand(run, request, "labex-agent-" + sanitize(run.runId()));
    }

    List<String> buildDockerTerminalCommand(WorkerRunSpec run, TerminalSpec terminal) {
        return buildDockerTerminalCommand(run, terminal, "labex-agent-" + sanitize(run.runId()));
    }

    private List<String> buildDockerCommand(WorkerRunSpec run, ProcessExecutionRequest request, String containerName) {
        WorkerPolicy policy = run.policy();
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("--name");
        command.add(containerName);
        command.add("--network");
        command.add(policy.networkEnabled() ? "bridge" : "none");
        command.add("--read-only");
        command.add("--tmpfs");
        command.add("/tmp:rw,noexec,nosuid,size=256m");
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--security-opt");
        command.add("no-new-privileges:true");
        command.add("--pids-limit");
        command.add(String.valueOf(policy.maxPids()));
        command.add("--memory");
        command.add(policy.memoryMegabytes() + "m");
        command.add("--cpus");
        command.add(String.format(java.util.Locale.ROOT, "%.3f", policy.cpuMillis() / 1000.0));
        command.add("--mount");
        command.add("type=bind,src=" + daemonWorkspacePath(run.workspaceRoot()) + ",dst=/workspace");
        command.add("--workdir");
        command.add(toContainerPath(run, request.workingDirectory()));
        appendRuntimeEnvironment(command);
        command.add("--env");
        command.add("TERM=xterm-256color");
        command.add(configuredImage.isBlank() ? policy.containerImage() : configuredImage);
        command.addAll(toContainerCommand(run, request.command()));
        return List.copyOf(command);
    }

    /**
     * Windows 控制面把 docker daemon 运行在 WSL 里时，daemon 只认识 /mnt/&lt;drive&gt; 路径；
     * 开启 wsl-workspace-mapping 后把 D:\foo 转成 /mnt/d/foo。Linux 控制面不受影响。
     */
    private String daemonWorkspacePath(Path workspaceRoot) {
        String path = workspaceRoot.toString();
        if (!wslWorkspaceMapping || path.length() < 2 || path.charAt(1) != ':') {
            return path;
        }
        char drive = Character.toLowerCase(path.charAt(0));
        return "/mnt/" + drive + path.substring(2).replace('\\', '/');
    }

    private List<String> buildDockerProcessCommand(
            WorkerRunSpec run, ProcessExecutionRequest request, String containerName) {
        List<String> command = new ArrayList<>(buildDockerCommand(run, request, containerName));
        int removeIndex = command.indexOf("--rm");
        command.add(removeIndex + 1, "-i");
        return List.copyOf(command);
    }

    private List<String> buildDockerTerminalCommand(
            WorkerRunSpec run, TerminalSpec terminal, String containerName) {
        WorkerPolicy policy = run.policy();
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("-i");
        command.add("-t");
        command.add("--name");
        command.add(containerName);
        command.add("--network");
        command.add(policy.networkEnabled() ? "bridge" : "none");
        command.add("--read-only");
        command.add("--tmpfs");
        command.add("/tmp:rw,noexec,nosuid,size=256m");
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--security-opt");
        command.add("no-new-privileges:true");
        command.add("--pids-limit");
        command.add(String.valueOf(policy.maxPids()));
        command.add("--memory");
        command.add(policy.memoryMegabytes() + "m");
        command.add("--cpus");
        command.add(String.format(java.util.Locale.ROOT, "%.3f", policy.cpuMillis() / 1000.0));
        command.add("--mount");
        command.add("type=bind,src=" + daemonWorkspacePath(run.workspaceRoot()) + ",dst=/workspace");
        command.add("--workdir");
        command.add(toContainerPath(run, terminal.workingDirectory()));
        appendRuntimeEnvironment(command);
        command.add("--env");
        command.add("TERM=xterm-256color");
        command.add("--env");
        command.add("COLUMNS=" + terminal.cols());
        command.add("--env");
        command.add("LINES=" + terminal.rows());
        command.add(configuredImage.isBlank() ? policy.containerImage() : configuredImage);
        command.add("/bin/sh");
        command.add("-i");
        return List.copyOf(command);
    }

    private void appendRuntimeEnvironment(List<String> command) {
        command.add("--env");
        command.add("HOME=" + CONTAINER_RUNTIME_ROOT + "/home");
        command.add("--env");
        command.add("NPM_CONFIG_CACHE=" + CONTAINER_RUNTIME_ROOT + "/npm-cache");
        command.add("--env");
        command.add("XDG_CACHE_HOME=" + CONTAINER_RUNTIME_ROOT + "/cache");
    }

    @Override
    public void terminate(String runId) {
        String prefix = "labex-agent-" + sanitize(runId) + "-";
        activeContainers.stream()
                .filter(container -> container.startsWith(prefix))
                .toList()
                .forEach(this::removeContainer);
    }

    private List<String> toContainerCommand(WorkerRunSpec run, List<String> requestCommand) {
        List<String> command = new ArrayList<>(requestCommand.size());
        for (String part : requestCommand) {
            command.add(toContainerPathIfWorkspaceChild(run, part));
        }
        return command;
    }

    private String toContainerPathIfWorkspaceChild(WorkerRunSpec run, String value) {
        try {
            Path candidate = Path.of(value).toAbsolutePath().normalize();
            if (candidate.startsWith(run.workspaceRoot())) {
                return toContainerPath(run, candidate);
            }
        } catch (Exception ignored) {
            // Non-path command arguments are forwarded unchanged.
        }
        return value;
    }

    private String toContainerPath(WorkerRunSpec run, Path path) {
        Path relative = run.workspaceRoot().relativize(path.toAbsolutePath().normalize());
        if (relative.getNameCount() == 0 || relative.toString().isBlank()) {
            return "/workspace";
        }
        return "/workspace/" + relative.toString().replace('\\', '/');
    }

    private String containerName(String runId) {
        return "labex-agent-" + sanitize(runId) + "-" + invocationCounter.incrementAndGet();
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    private void removeContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // The container may already have been removed by --rm.
        }
    }
}
