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
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
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
 * OpenCode-first Shell Tool. The model command is deliberately passed as one complete Shell payload;
 * command parsing belongs to the selected Bash/PowerShell implementation, never to Java whitespace splitting.
 */
@Component
public class RunCommandTool implements AgentTool {
    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final int MAX_TIMEOUT_MS = 600_000;
    private static final int MAX_OUTPUT_CHARS = 60_000;

    private final SandboxWorker sandboxWorker;
    private final AgentExecutionProperties executionProperties;
    private final CommandClassifier commandClassifier = new CommandClassifier();

    /** Compatibility constructor for focused tests and legacy callers. */
    public RunCommandTool(SandboxWorker sandboxWorker) {
        this(sandboxWorker, new AgentExecutionProperties());
    }

    @Autowired
    public RunCommandTool(SandboxWorker sandboxWorker, AgentExecutionProperties executionProperties) {
        this.sandboxWorker = sandboxWorker;
        this.executionProperties = executionProperties == null ? new AgentExecutionProperties() : executionProperties;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("shell")
                .description("在 Worker 中执行完整 Bash 或 PowerShell 命令；请优先使用 workdir，不要用 cd 替代工作目录参数。")
                .stringProperty("command", "完整 Shell 命令字符串", true)
                .intProperty("timeout", "超时毫秒数，默认按命令类别计算", false)
                .stringProperty("workdir", "workspace 内相对工作目录，不填则使用根目录", false)
                .stringProperty("description", "简短的人类可读执行说明，5-10 个词", false)
                // 兼容旧模型输入；系统提示词使用 workdir/timeout。
                .stringProperty("working_directory", "兼容字段：映射到 workdir", false)
                .stringProperty("workingDirectory", "兼容字段：映射到 workdir", false)
                .stringProperty("cwd", "兼容字段：映射到 workdir", false)
                .intProperty("timeout_seconds", "兼容字段：映射到 timeout 毫秒", false)
                .booleanProperty("network", "兼容字段；opencode profile 由执行配置决定网络能力", false)
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
        return ToolResult.fromProcessExecution(shellExecutor.execute(run, prepared, context.getCancellationToken()),
                descriptor.shellName(), relativeWorkdir(context, workingPath), artifact.relativePath());
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
            return ToolResult.fromProcessExecution(sandboxWorker.execute(run, new ProcessExecutionRequest(
                    argv, workingPath, Duration.ofMillis(timeoutMs), MAX_OUTPUT_CHARS,
                    artifact.absolutePath()), context.getCancellationToken()),
                    "direct", relativeWorkdir(context, workingPath), artifact.relativePath());
        } catch (IllegalArgumentException exception) {
            return ToolResult.failed(exception.getMessage());
        }
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
