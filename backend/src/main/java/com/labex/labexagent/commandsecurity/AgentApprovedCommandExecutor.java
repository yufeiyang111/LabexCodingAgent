package com.labex.labexagent.commandsecurity;

import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ProcessExecutionObserver;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.ShellCommandFactory;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.network.NetworkAccessService;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.labexagent.workspace.ProjectWorkspace;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/** Executes only a persisted, consumed agent command approval through the sandbox worker. */
@Service
public class AgentApprovedCommandExecutor {
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final SandboxWorker sandboxWorker;
    private final NetworkAccessService networkAccessService;

    public AgentApprovedCommandExecutor(SandboxWorker sandboxWorker) {
        this(sandboxWorker, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentApprovedCommandExecutor(SandboxWorker sandboxWorker, NetworkAccessService networkAccessService) {
        this.sandboxWorker = sandboxWorker;
        this.networkAccessService = networkAccessService;
    }

    public ProcessExecutionResult execute(CommandApproval approval, StudentProject project) {
        return execute(approval, project, CancellationToken.none());
    }

    public ProcessExecutionResult execute(CommandApproval approval, StudentProject project,
                                          CancellationToken cancellationToken) {
        return execute(approval, project, cancellationToken, ProcessExecutionObserver.none());
    }

    public ProcessExecutionResult execute(CommandApproval approval, StudentProject project,
                                          CancellationToken cancellationToken,
                                          ProcessExecutionObserver observer) {
        boolean networkEnabled = approval != null
                && networkEnabled(approval.getCommandOptions())
                && networkAccessService != null
                && networkAccessService.consumeGrant(approval.getStudentId(), approval.getProjectId(),
                approval.getTaskId(), approval.getCanonicalCommand());
        return executeInternal(approval, project, cancellationToken, observer, networkEnabled);
    }

    /** 已批准的离线失败重试由服务端直接启用网络，不再消费第二份授权。 */
    public ProcessExecutionResult executeNetworkRetry(CommandApproval approval, StudentProject project,
                                                       CancellationToken cancellationToken,
                                                       ProcessExecutionObserver observer) {
        return executeInternal(approval, project, cancellationToken, observer, true);
    }

    private ProcessExecutionResult executeInternal(CommandApproval approval, StudentProject project,
                                                    CancellationToken cancellationToken,
                                                    ProcessExecutionObserver observer,
                                                    boolean networkEnabled) {
        if (approval == null || project == null || approval.getTaskId() == null) {
            throw new IllegalArgumentException("approved command and project are required");
        }
        if (!"agent_shell".equals(approval.getSource()) || !"consumed".equals(approval.getStatus())) {
            throw new IllegalArgumentException("agent command approval is not consumable");
        }
        Path workspaceRoot = ProjectWorkspace.paths(project).workspaceRoot();
        Path workingDirectory = ProjectWorkspace.paths(project).resolveExisting(approval.getWorkingDirectory());
        int timeoutSeconds = timeoutSeconds(approval.getCommandOptions());
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("task-" + approval.getTaskId(), workspaceRoot, networkEnabled);
        List<String> command = approvedCommand(approval, run);
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                command, workingDirectory, Duration.ofSeconds(timeoutSeconds), MAX_OUTPUT_CHARS);
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        ProcessExecutionObserver effectiveObserver = observer == null ? ProcessExecutionObserver.none() : observer;
        return sandboxWorker.execute(run, request, token, effectiveObserver);
    }

    private List<String> approvedCommand(CommandApproval approval, WorkerRunSpec run) {
        if (approval.getShell() == null || approval.getShell().isBlank()
                || "direct".equalsIgnoreCase(approval.getShell())) {
            return DirectCommandTokenizer.tokenize(approval.getCanonicalCommand());
        }
        WorkerShellDescriptor descriptor = sandboxWorker.shellDescriptor(run);
        if (descriptor == null) {
            descriptor = sandboxWorker.usesLinuxShell()
                    ? WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", run.policy().networkEnabled())
                    : WorkerShellDescriptor.powerShell("windows", "powershell.exe",
                    run.workspaceRoot().toString(), run.policy().networkEnabled());
        }
        return ShellCommandFactory.create(descriptor, approval.getCanonicalCommand());
    }

    private boolean networkEnabled(String options) {
        if (options == null) return false;
        for (String part : options.split(";")) {
            if ("network=true".equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }

    private int timeoutSeconds(String options) {
        if (options == null || !options.startsWith("timeout=")) {
            throw new IllegalArgumentException("approved command options are invalid");
        }
        try {
            int separator = options.indexOf(';');
            int parsed = Integer.parseInt(options.substring("timeout=".length(),
                    separator < 0 ? options.length() : separator));
            return Math.min(600, Math.max(1, parsed));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("approved command options are invalid", exception);
        }
    }
}
