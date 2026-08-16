package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandPolicyMessage;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.DirectCommandTokenizer;
import com.labex.labexagent.commandsecurity.DirectCommandWorkingDirectory;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.WorkerShellExecutor;
import com.labex.labexagent.run.AgentVerificationRecorder;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.workspace.WorkspaceOperationIdentity;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Labex standard Shell Tool. The model command is deliberately passed as one complete Shell payload;
 * command parsing belongs to the selected Bash/PowerShell implementation, never to Java whitespace splitting.
 */
@Component
public class RunCommandTool implements AgentTool {
    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final int MAX_TIMEOUT_MS = 600_000;
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final SandboxWorker sandboxWorker;
    private final AgentExecutionProperties executionProperties;
    private final AgentVerificationRecorder verificationRecorder;
    private final CommandClassifier commandClassifier = new CommandClassifier();

    /** Compatibility constructor for focused tests and legacy callers. */
    public RunCommandTool(SandboxWorker sandboxWorker) {
        this(sandboxWorker, new AgentExecutionProperties(), null);
    }

    /** Compatibility constructor for focused tests and worker smoke coverage. */
    public RunCommandTool(SandboxWorker sandboxWorker, AgentExecutionProperties executionProperties) {
        this(sandboxWorker, executionProperties, null);
    }

    @Autowired
    public RunCommandTool(SandboxWorker sandboxWorker, AgentExecutionProperties executionProperties,
                          AgentVerificationRecorder verificationRecorder) {
        this.sandboxWorker = sandboxWorker;
        this.executionProperties = executionProperties == null ? new AgentExecutionProperties() : executionProperties;
        this.verificationRecorder = verificationRecorder;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("shell")
                .description("Run a complete Bash or PowerShell command in the worker. Prefer workdir instead of using cd to select the working directory.")
                .stringProperty("command", "Complete shell command string", true)
                .intProperty("timeout", "Timeout in milliseconds; defaults by command category", false)
                .stringProperty("workdir", "Workspace-relative working directory; defaults to the workspace root", false)
                .stringProperty("description", "Short human-readable execution summary, 5-10 words", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String command = ToolSupport.stringArgMulti(args, "", "command", "cmd", "shell_command");
        if (command.isBlank()) {
            return ToolResult.failed("command is required");
        }
        String requestedWorkdir = ToolSupport.stringArgMulti(
                args, "", "workdir", "working_directory", "workingDirectory", "cwd");
        int timeoutMs = timeoutMs(args, command);
        WorkerRunSpec run = workerRun(context);

        if (executionProperties.isSafeProfile()) {
            return executeRestrictedDirectCommand(context, command, requestedWorkdir, timeoutMs, run);
        }

        WorkerShellExecutor shellExecutor = new WorkerShellExecutor(sandboxWorker);
        WorkerShellDescriptor descriptor = shellExecutor.descriptor(run);
        // 网络请求标记只来自模型显式参数；Worker 网络能力由执行配置决定，不是本次命令的网络意图。
        boolean networkRequested = args.has("network") && !args.get("network").isJsonNull()
                && args.get("network").getAsBoolean();
        CommandClassification classification = commandClassifier.classify(new CommandRequest(
                command,
                descriptor.shellName(),
                requestedWorkdir,
                timeoutSeconds(timeoutMs),
                false,
                networkRequested,
                executionProperties.getPermissionProfile()));
        ToolResult policyFailure = policyFailure(classification);
        if (policyFailure != null) {
            return policyFailure;
        }

        Path workingPath = resolveWorkdir(context, requestedWorkdir);
        ToolSupport.ProcessOutputArtifact artifact = ToolSupport.processOutputArtifact(
                context, definition().getName(), currentToolCallId());
        WorkerShellExecutor.PreparedExecution prepared = shellExecutor.prepare(
                descriptor, command, workingPath, Duration.ofMillis(timeoutMs),
                MAX_OUTPUT_CHARS, artifact.absolutePath());
        ToolResult result = ToolResult.fromObservedProcessExecution(
                shellExecutor.execute(run, prepared, context.getCancellationToken()),
                descriptor.shellName(), relativeWorkdir(context, workingPath), artifact.relativePath());
        result.withWorkspaceIdentity(WorkspaceOperationIdentity.forContext(context, workingPath, List.of()));
        recordVerificationIfRelevant(context, command, result);
        return result;
    }

    private ToolResult executeRestrictedDirectCommand(AgentContext context, String command, String requestedWorkdir,
                                                      int timeoutMs, WorkerRunSpec run) {
        DirectCommandWorkingDirectory.Normalized normalized;
        try {
            normalized = DirectCommandWorkingDirectory.normalize(command, requestedWorkdir);
        } catch (IllegalArgumentException exception) {
            return ToolResult.failed("failure_code=unsafe_working_directory\nretryable=true\n" + exception.getMessage());
        }
        CommandClassification classification = commandClassifier.classify(new CommandRequest(
                normalized.command(), "direct", normalized.workingDirectory(), timeoutSeconds(timeoutMs), false,
                false, "safe"));
        ToolResult policyFailure = policyFailure(classification);
        if (policyFailure != null) {
            return policyFailure;
        }
        Path workingPath = resolveWorkdir(context, normalized.workingDirectory());
        try {
            List<String> argv = DirectCommandTokenizer.tokenize(classification.normalizedCommand().canonicalCommand());
            ToolSupport.ProcessOutputArtifact artifact = ToolSupport.processOutputArtifact(
                    context, definition().getName(), currentToolCallId());
            ToolResult result = ToolResult.fromObservedProcessExecution(
                    sandboxWorker.execute(run, new ProcessExecutionRequest(
                            argv, workingPath, Duration.ofMillis(timeoutMs), MAX_OUTPUT_CHARS,
                            artifact.absolutePath()), context.getCancellationToken()),
                    "direct", relativeWorkdir(context, workingPath), artifact.relativePath());
            result.withWorkspaceIdentity(WorkspaceOperationIdentity.forContext(context, workingPath, List.of()));
            recordVerificationIfRelevant(context, command, result);
            return result;
        } catch (IllegalArgumentException exception) {
            return ToolResult.failed(exception.getMessage());
        }
    }

    private void recordVerificationIfRelevant(AgentContext context, String command, ToolResult result) {
        if (verificationRecorder == null || context == null || context.getTaskId() == null
                || context.getStudentId() == null || context.getProject() == null
                || context.getProject().getProjectId() == null) {
            return;
        }
        if (context.getExecutionFence() == null) {
            verificationRecorder.recordShellToolResult(
                    context.getTaskId(), context.getStudentId(), context.getProject().getProjectId(), command, result);
            return;
        }
        verificationRecorder.recordShellToolResult(context.getExecutionFence(), context.getTaskId(),
                context.getStudentId(), context.getProject().getProjectId(), command, result);
    }

    private ToolResult policyFailure(CommandClassification classification) {
        if (classification.decision() == CommandDecision.ALLOW) {
            return null;
        }
        if (classification.requiresApproval()) {
            return ToolResult.failed("runtime_protocol_error=command_approval_not_persisted\n"
                    + "Command approval must be created by the Agent runtime before tool delegation.");
        }
        return ToolResult.failed("command blocked by command policy\n"
                + "reason=" + classification.reasonCode().name().toLowerCase() + "\n"
                + "hint=" + CommandPolicyMessage.forReason(classification.reasonCode()));
    }

    private Path resolveWorkdir(AgentContext context, String requestedWorkdir) {
        if (requestedWorkdir == null || requestedWorkdir.isBlank()) {
            return context.getWorkspaceRoot().toAbsolutePath().normalize();
        }
        try {
            Path workingPath = ToolSupport.resolve(context, requestedWorkdir);
            if (!Files.isDirectory(workingPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workdir must be an existing directory");
            }
            return workingPath;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsafe workdir: " + exception.getMessage(), exception);
        }
    }

    private String currentToolCallId() {
        return com.labex.labexagent.runtime.AgentToolTurnExecutor.currentToolCallId();
    }

    private String relativeWorkdir(AgentContext context, Path workdir) {
        Path workspace = context.getWorkspaceRoot().toAbsolutePath().normalize();
        Path normalized = workdir.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) {
            return ".";
        }
        String relative = workspace.relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private int timeoutMs(JsonObject args, String command) {
        if (args != null && args.has("timeout") && !args.get("timeout").isJsonNull()) {
            try {
                return Math.min(MAX_TIMEOUT_MS, Math.max(1, args.get("timeout").getAsInt()));
            } catch (RuntimeException ignored) {
                return DEFAULT_TIMEOUT_MS;
            }
        }
        if (args != null && args.has("timeout_seconds") && !args.get("timeout_seconds").isJsonNull()) {
            int legacySeconds = ToolSupport.intArg(args, "timeout_seconds", DEFAULT_TIMEOUT_MS / 1_000);
            return Math.min(MAX_TIMEOUT_MS, Math.max(1_000, legacySeconds * 1_000));
        }
        return Math.min(MAX_TIMEOUT_MS,
                com.labex.labexagent.execution.ShellCommandTimeouts.defaultTimeoutMs(command));
    }

    private int timeoutSeconds(int timeoutMs) {
        return Math.max(1, (int) Math.ceil(timeoutMs / 1_000.0));
    }

    private WorkerRunSpec workerRun(AgentContext context) {
        String runId = context.getTaskId() == null
                ? "agent-" + context.getSessionId()
                : "task-" + context.getTaskId();
        boolean networkEnabled = context.isNetworkEnabled() || executionProperties.isNetworkDefaultEnabled();
        return WorkerRunSpec.forWorkspace(runId, context.getWorkspaceRoot(), networkEnabled);
    }
}
