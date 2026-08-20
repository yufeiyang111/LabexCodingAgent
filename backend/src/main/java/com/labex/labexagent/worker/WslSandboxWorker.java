package com.labex.labexagent.worker;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionObserver;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.ProcessExecutor;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.terminal.TerminalSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local Windows worker that executes tools inside the installed WSL distribution using bubblewrap.
 * Only the project workspace is bind-mounted into the sandbox as /workspace.
 */
@Component
@Profile({"local", "dev", "wsl"})
public class WslSandboxWorker extends LocalDevelopmentWorker {
    private static final String SANDBOX_ROOT = "/workspace";
    private static final String RUNTIME_ROOT = SANDBOX_ROOT + "/.labex-agent/runtime";

    private final ProcessExecutor processExecutor;
    private final String distribution;

    @Autowired
    public WslSandboxWorker(
            ProcessExecutor processExecutor,
            @Value("${labex-agent.worker.wsl.distribution:${LABEX_AGENT_WSL_DISTRIBUTION:Debian}}") String distribution) {
        super(processExecutor);
        this.processExecutor = processExecutor;
        this.distribution = distribution == null || distribution.isBlank() ? "Debian" : distribution.trim();
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
        CancellationToken sourceToken = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (sourceToken.isCancellationRequested()) {
            return new ProcessExecutionResult(ExecutionStatus.CANCELLED, null, 0, "", false);
        }
        try {
            prepare(run);
            requireWorkspacePath(run, request.workingDirectory());
            try (WslCommandSupervisor.Execution supervision =
                         WslCommandSupervisor.open(run, request, sourceToken)) {
                ProcessExecutionRequest sandboxRequest = new ProcessExecutionRequest(
                        buildWslCommand(run, request, supervision.command()),
                        run.workspaceRoot(),
                        supervision.processTimeout(),
                        request.maxOutputChars(),
                        run.policy().safeEnvironment(run.workspaceRoot(), System.getenv()),
                        request.outputArtifactPath());
                ProcessExecutionResult result = processExecutor.execute(
                        sandboxRequest, supervision.cancellationToken(), chunk -> { },
                        identity -> observer.onStarted(identity.withWorkerContext("wsl", run.runId())));
                return supervision.translate(result);
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
        ProcessBuilder processBuilder = new ProcessBuilder(buildWslCommand(run, request))
                .directory(run.workspaceRoot().toFile());
        processBuilder.environment().clear();
        processBuilder.environment().putAll(run.policy().safeEnvironment(run.workspaceRoot(), System.getenv()));
        return workerProcess(processBuilder.start(), () -> { });
    }



    @Override
    public boolean usesLinuxShell() {
        return true;
    }

    @Override
    public WorkerShellDescriptor shellDescriptor(WorkerRunSpec run) {
        return WorkerShellDescriptor.bash(
                "linux-wsl", "/bin/bash", SANDBOX_ROOT, run != null && run.policy().networkEnabled());
    }

    @Override
    public String workspaceUri(WorkerRunSpec run, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        requireWorkspacePath(run, normalized);
        String relative = run.workspaceRoot().relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "file:///workspace" : "file:///workspace/" + relative;
    }

    @Override
    public Path hostPath(WorkerRunSpec run, String workerUri) {
        try {
            String sandboxPath = java.net.URI.create(workerUri).getPath();
            if (sandboxPath == null || !(sandboxPath.equals(SANDBOX_ROOT) || sandboxPath.startsWith(SANDBOX_ROOT + "/"))) {
                throw new IllegalArgumentException("worker URI escapes workspace");
            }
            String relative = sandboxPath.equals(SANDBOX_ROOT) ? "" : sandboxPath.substring((SANDBOX_ROOT + "/").length());
            Path resolved = run.workspaceRoot().resolve(relative).normalize();
            requireWorkspacePath(run, resolved);
            return resolved;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("worker URI is invalid", e);
        }
    }

    List<String> buildWslCommand(WorkerRunSpec run, ProcessExecutionRequest request) {
        return buildWslCommand(run, request, request.command());
    }

    private List<String> buildWslCommand(
            WorkerRunSpec run, ProcessExecutionRequest request, List<String> commandArguments) {
        requireWorkspacePath(run, request.workingDirectory());
        String workspace = toWslPath(run.workspaceRoot());
        String workingDirectory = toSandboxPath(run, request.workingDirectory());
        List<String> command = new ArrayList<>();
        command.add("wsl.exe");
        command.add("-d");
        command.add(distribution);
        command.add("--exec");
        command.add("bwrap");
        command.add("--die-with-parent");
        command.add("--new-session");
        if (!run.policy().networkEnabled()) {
            command.add("--unshare-net");
        }
        command.add("--unshare-ipc");
        command.add("--unshare-uts");
        command.add("--unshare-pid");
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--ro-bind");
        command.add("/usr");
        command.add("/usr");
        command.add("--ro-bind");
        command.add("/bin");
        command.add("/bin");
        command.add("--ro-bind");
        command.add("/lib");
        command.add("/lib");
        command.add("--ro-bind");
        command.add("/lib64");
        command.add("/lib64");
        command.add("--dir");
        command.add("/etc");
        if (run.policy().networkEnabled()) {
            // 网络开启时只读挂载 DNS、hosts 与系统 CA；Java cacerts 会链接到 /etc/ssl/certs/java/cacerts。
            readOnlyBind(command, "/etc/resolv.conf");
            readOnlyBind(command, "/etc/hosts");
            readOnlyBind(command, "/etc/ssl");
        }
        readOnlyBind(command, "/etc/alternatives");
        // Maven 和 OpenJDK 运行时依赖的只读 /etc 子目录。
        readOnlyBind(command, "/etc/maven");
        readOnlyBind(command, "/etc/java-21-openjdk");
        command.add("--dir");
        command.add("/home");
        command.add("--dir");
        command.add("/root");
        command.add("--proc");
        command.add("/proc");
        command.add("--dev");
        command.add("/dev");
        command.add("--tmpfs");
        command.add("/tmp");
        command.add("--bind");
        command.add(workspace);
        command.add(SANDBOX_ROOT);
        command.add("--clearenv");
        setEnvironment(command, "HOME", RUNTIME_ROOT + "/home");
        setEnvironment(command, "NPM_CONFIG_CACHE", RUNTIME_ROOT + "/npm-cache");
        setEnvironment(command, "XDG_CACHE_HOME", RUNTIME_ROOT + "/cache");
        setEnvironment(command, "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        setEnvironment(command, "LANG", "C.UTF-8");
        command.add("--chdir");
        command.add(workingDirectory);
        command.addAll(commandArguments);
        return List.copyOf(command);
    }

    private void readOnlyBind(List<String> command, String path) {
        command.add("--ro-bind");
        command.add(path);
        command.add(path);
    }

    private void setEnvironment(List<String> command, String name, String value) {
        command.add("--setenv");
        command.add(name);
        command.add(value);
    }

    private String toSandboxPath(WorkerRunSpec run, Path path) {
        Path relative = run.workspaceRoot().relativize(path.toAbsolutePath().normalize());
        return relative.toString().isBlank() ? SANDBOX_ROOT : SANDBOX_ROOT + "/" + relative.toString().replace('\\', '/');
    }

    private String toWslPath(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (normalized.length() < 3 || normalized.charAt(1) != ':' || normalized.charAt(2) != '/') {
            throw new IllegalArgumentException("WSL worker requires a Windows drive path");
        }
        return "/mnt/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
    }
}
