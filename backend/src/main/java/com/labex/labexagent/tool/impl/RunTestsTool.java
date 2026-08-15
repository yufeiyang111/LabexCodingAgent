package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.TestCommandResolver;
import com.labex.labexagent.commandsecurity.VerificationStrategy;
import com.labex.labexagent.execution.ShellCommandFactory;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.WorkerShellExecutor;
import com.labex.labexagent.run.AgentRecoveryProperties;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.run.AgentVerificationRecorder;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.runtime.AgentToolTurnExecutor;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.mapper.AgentVerificationMapper;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RunTestsTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(RunTestsTool.class);
    private final SandboxWorker sandboxWorker;
    private final CommandClassifier commandClassifier = new CommandClassifier();
    private final AgentVerificationRecorder verificationRecorder;
    private AgentExecutionProperties executionProperties = new AgentExecutionProperties();
    private AgentRecoveryProperties recoveryProperties = new AgentRecoveryProperties();

    @Value("${labex-agent.acceptance.auto-approve-verification:false}")
    private boolean acceptanceAutoApproveVerification;

    public RunTestsTool(SandboxWorker sandboxWorker) {
        this(sandboxWorker, null, new AgentExecutionProperties());
    }

    public RunTestsTool(SandboxWorker sandboxWorker, AgentVerificationRecorder verificationRecorder,
                        AgentExecutionProperties executionProperties) {
        this.sandboxWorker = sandboxWorker;
        this.verificationRecorder = verificationRecorder;
        this.executionProperties = executionProperties == null ? new AgentExecutionProperties() : executionProperties;
    }

    @Autowired
    public RunTestsTool(SandboxWorker sandboxWorker, AgentVerificationRecorder verificationRecorder) {
        this.sandboxWorker = sandboxWorker;
        this.verificationRecorder = verificationRecorder;
    }

    public RunTestsTool(SandboxWorker sandboxWorker, AgentVerificationMapper verificationMapper,
                        AgentRunArtifactService artifactService) {
        this(sandboxWorker, verificationMapper, artifactService, null);
    }

    public RunTestsTool(SandboxWorker sandboxWorker, AgentVerificationMapper verificationMapper,
                        AgentRunArtifactService artifactService, AgentRecoveryProperties recoveryProperties) {
        this.sandboxWorker = sandboxWorker;
        this.verificationRecorder = verificationMapper == null || artifactService == null
                ? null : new AgentVerificationRecorder(verificationMapper, artifactService);
        this.executionProperties = new AgentExecutionProperties();
        this.recoveryProperties = recoveryProperties;
    }

    @Autowired(required = false)
    void setRecoveryProperties(AgentRecoveryProperties recoveryProperties) {
        if (recoveryProperties != null) this.recoveryProperties = recoveryProperties;
    }

    @Autowired(required = false)
    void setExecutionProperties(AgentExecutionProperties executionProperties) {
        if (executionProperties != null) this.executionProperties = executionProperties;
    }
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("run_tests")
                .description("Run a restricted direct argv command under the server verification policy. Supports auto, test, compile, build, offline_test, and manual.")
                .stringProperty("strategy", "Verification strategy: auto, test, compile, build, offline_test, or manual; defaults to server configuration", false)
                .stringProperty("target_path", "Project file or module path to verify; call separately for changes spanning multiple modules", false)
                .intProperty("timeout_seconds", "Timeout in seconds; defaults by build tool: Maven/Gradle 300, Node 240, Python 180", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        VerificationStrategy configured = recoveryProperties == null
                ? VerificationStrategy.AUTO : recoveryProperties.getVerificationStrategy();
        VerificationStrategy strategy = context.isEnvironmentRecovery()
                ? configured
                : VerificationStrategy.parse(
                        args.has("strategy") ? args.get("strategy").getAsString() : null, configured);

        VerificationStrategy fallback = recoveryProperties == null
                ? VerificationStrategy.MANUAL : recoveryProperties.getFallbackVerificationStrategy();
        List<String> verificationTargets = verificationTargets(context, args);
        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                context.getWorkspaceRoot(), strategy, fallback, verificationTargets);
        List<String> command = resolved.command();
        if (command.isEmpty()) {
            log.warn("RUN_TESTS_NO_COMMAND taskId={} workspace={} strategy={} fallback={} targets={} reason={}",
                    context.getTaskId(), context.getWorkspaceRoot(), strategy, fallback, verificationTargets, resolved.reason());
            String reason = resolved.reason().isBlank()
                    ? "无法识别项目类型或按当前策略没有可用验证命令"
                    : resolved.reason();
            return ToolResult.failed("failure_code=verification_command_unavailable\nretryable=true\n" + reason);
        }
        int timeout = verificationTimeout(args, command);
        WorkerRunSpec run = workerRun(context);
        WorkerShellExecutor shellExecutor = new WorkerShellExecutor(sandboxWorker);
        WorkerShellDescriptor descriptor = shellExecutor.descriptor(run);
        String shellCommand = ShellCommandFactory.renderArguments(descriptor, command);
        String workingDirectory = relativeWorkingDirectory(context.getWorkspaceRoot(), resolved.workingDirectory());
        // 服务端验证策略命令不携带模型网络请求标记；网络能力由审批与 Worker 配置决定。
        // 分类基于真实命令身份（未渲染 argv），PowerShell/Bash 渲染细节不得改变策略判断。
        String commandIdentity = String.join(" ", command);
        CommandClassification classification = commandClassifier.classify(new CommandRequest(
                commandIdentity, descriptor.shellName(), workingDirectory, timeout, false,
                false, executionProperties.getPermissionProfile()));
        if (classification.decision() != CommandDecision.ALLOW
                && !(classification.requiresApproval() && acceptanceAutoApproveVerification)) {
            if (classification.requiresApproval()) {
                return ToolResult.failed("runtime_protocol_error=command_approval_not_persisted\n"
                        + "Command approval must be created by the Agent runtime before tool delegation and carry a persisted approvalId.");
            }
            return ToolResult.failed("command blocked by verification command policy: "
                    + classification.reasonCode().name().toLowerCase());
        }
        ToolSupport.ProcessOutputArtifact artifact = ToolSupport.processOutputArtifact(
                context, definition().getName(), AgentToolTurnExecutor.currentToolCallId());
        WorkerShellExecutor.PreparedExecution prepared = shellExecutor.prepare(
                descriptor, shellCommand, resolved.workingDirectory(), Duration.ofSeconds(timeout),
                60_000, artifact.absolutePath());
        ToolResult result = ToolResult.fromProcessExecution(
                shellExecutor.execute(run, prepared, context.getCancellationToken()),
                descriptor.shellName(), workingDirectory, artifact.relativePath());
        result.setContent("verification_command=" + classification.normalizedCommand().displayCommand()
                + "\nworking_directory=" + workingDirectory
                + "\ntimeout_seconds=" + timeout
                + "\n" + result.getContent());
        // 环境受阻（超时/取消/基础设施）必须与代码测试失败分开：给模型可行动的环境恢复指引。
        String executionStatus = result.getExecutionStatus();
        if (!result.isSuccess() && executionStatus != null) {
            String envHint = switch (executionStatus) {
                case "timed_out" -> "failure_code=VERIFICATION_TIMED_OUT\nretryable=true\n"
                        + "recovery_action=increase_timeout_seconds_or_verify_a_smaller_target_path\n";
                case "cancelled" -> "failure_code=VERIFICATION_CANCELLED\nretryable=false\n"
                        + "recovery_action=rerun_after_resolving_the_cancellation_source\n";
                case "infrastructure_error" -> "failure_code=VERIFICATION_INFRASTRUCTURE_ERROR\nretryable=true\n"
                        + "recovery_action=restore_environment_or_switch_verification_strategy\n";
                default -> "";
            };
            if (!envHint.isEmpty()) {
                result.setContent(envHint + result.getContent());
            }
        }
        VerificationStrategy effectiveStrategy = strategy == VerificationStrategy.MANUAL
                && fallback != VerificationStrategy.MANUAL ? fallback : strategy;
        recordVerification(context, classification.normalizedCommand().displayCommand(), effectiveStrategy, result);
        if (!result.isSuccess()) {
            String output = result.getContent() == null ? "" : result.getContent().replaceAll("\\s+", " ");
            log.warn("RUN_TESTS_FAILED taskId={} strategy={} command={} output={}",
                    context.getTaskId(), effectiveStrategy, classification.normalizedCommand().displayCommand(),
                    output.length() <= 500 ? output : output.substring(0, 500));
        }
        return result;
    }

    private List<String> verificationTargets(AgentContext context, JsonObject args) {
        if (args != null && args.has("target_path") && !args.get("target_path").isJsonNull()) {
            String target = args.get("target_path").getAsString();
            if (target != null && !target.isBlank()) {
                return List.of(target.trim().replace('\\', '/'));
            }
        }
        return context.getUnverifiedChangeTargets().stream().sorted().toList();
    }

    private int verificationTimeout(JsonObject args, List<String> command) {
        int defaultTimeout = TestCommandResolver.defaultTimeoutSeconds(command);
        try {
            int requested = args != null && args.has("timeout_seconds")
                    ? args.get("timeout_seconds").getAsInt() : defaultTimeout;
            return Math.min(600, Math.max(1, requested));
        } catch (RuntimeException exception) {
            return defaultTimeout;
        }
    }

    private String relativeWorkingDirectory(Path workspaceRoot, Path workingDirectory) {
        if (workspaceRoot == null || workingDirectory == null) {
            return ".";
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path resolved = workingDirectory.toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            return ".";
        }
        String relative = root.relativize(resolved).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private void recordVerification(AgentContext context, String command, VerificationStrategy strategy, ToolResult result) {
        if (context.getTaskId() == null || context.getProject() == null || verificationRecorder == null) return;
        verificationRecorder.recordToolResult(
                context.getExecutionFence(), context.getTaskId(), context.getStudentId(),
                context.getProject().getProjectId(), command, strategy.name().toLowerCase(), result);
    }

    private WorkerRunSpec workerRun(AgentContext context) {
        String runId = context.getTaskId() == null ? "agent-" + context.getSessionId() : "task-" + context.getTaskId();
        boolean networkEnabled = context.isNetworkEnabled() || executionProperties.isNetworkDefaultEnabled();
        return WorkerRunSpec.forWorkspace(runId, context.getWorkspaceRoot(), networkEnabled);
    }
}
