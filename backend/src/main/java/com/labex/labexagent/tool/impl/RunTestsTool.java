package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.entity.AgentVerification;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.TestCommandResolver;
import com.labex.labexagent.commandsecurity.VerificationStrategy;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.run.AgentRecoveryProperties;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.mapper.AgentVerificationMapper;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private final AgentVerificationMapper verificationMapper;
    private final AgentRunArtifactService artifactService;
    private AgentRecoveryProperties recoveryProperties = new AgentRecoveryProperties();

    @Value("${labex-agent.acceptance.auto-approve-verification:false}")
    private boolean acceptanceAutoApproveVerification;

    public RunTestsTool(SandboxWorker sandboxWorker) {
        this(sandboxWorker, null, null, null);
    }

    @Autowired
    public RunTestsTool(SandboxWorker sandboxWorker, AgentVerificationMapper verificationMapper,
                        AgentRunArtifactService artifactService) {
        this(sandboxWorker, verificationMapper, artifactService, null);
    }

    public RunTestsTool(SandboxWorker sandboxWorker, AgentVerificationMapper verificationMapper,
                        AgentRunArtifactService artifactService, AgentRecoveryProperties recoveryProperties) {
        this.sandboxWorker = sandboxWorker;
        this.verificationMapper = verificationMapper;
        this.artifactService = artifactService;
        this.recoveryProperties = recoveryProperties;
    }

    @Autowired(required = false)
    void setRecoveryProperties(AgentRecoveryProperties recoveryProperties) {
        if (recoveryProperties != null) this.recoveryProperties = recoveryProperties;
    }
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("run_tests")
                .description("按服务端验证策略运行受限的直接 argv 命令；支持 auto/test/compile/build/offline_test/manual。")
                .stringProperty("strategy", "验证策略：auto、test、compile、build、offline_test 或 manual；不传则使用服务端配置。", false)
                .intProperty("timeout_seconds", "超时时间（默认 120 秒）", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        int timeout = Math.min(600, Math.max(1, args.has("timeout_seconds") ? args.get("timeout_seconds").getAsInt() : 120));
        VerificationStrategy configured = recoveryProperties == null
                ? VerificationStrategy.AUTO : recoveryProperties.getVerificationStrategy();
        VerificationStrategy strategy = context.isEnvironmentRecovery()
                ? configured
                : VerificationStrategy.parse(
                        args.has("strategy") ? args.get("strategy").getAsString() : null, configured);

        VerificationStrategy fallback = recoveryProperties == null
                ? VerificationStrategy.MANUAL : recoveryProperties.getFallbackVerificationStrategy();
        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                context.getWorkspaceRoot(), strategy, fallback);
        VerificationStrategy effectiveStrategy = strategy;
        if (resolved.command().isEmpty() && fallback != VerificationStrategy.MANUAL && fallback != strategy) {
            resolved = TestCommandResolver.resolveProject(context.getWorkspaceRoot(), fallback);
            effectiveStrategy = fallback;
        }
        List<String> command = resolved.command();
        if (command.isEmpty()) {
            log.warn("RUN_TESTS_NO_COMMAND taskId={} workspace={} strategy={} fallback={}",
                    context.getTaskId(), context.getWorkspaceRoot(), strategy, fallback);
            return ToolResult.failed("无法识别项目类型或按策略 " + strategy.name().toLowerCase() + " 没有可用验证命令；请切换策略或进行手工验证。");
        }
        String canonicalCommand = String.join(" ", command);
        CommandClassification classification = commandClassifier.classify(new CommandRequest(
                canonicalCommand, "direct", ".", timeout, false, false, "agent-worker"));
        if (classification.decision() != CommandDecision.ALLOW
                && !(classification.requiresApproval() && acceptanceAutoApproveVerification)) {
            if (classification.requiresApproval()) {
                return ToolResult.approvalRequired("验证命令需要服务端一次性审批", canonicalCommand);
            }
            return ToolResult.failed("command blocked by restricted command policy: "
                    + classification.reasonCode().name().toLowerCase());
        }
        ToolResult result = ToolResult.fromProcessExecution(sandboxWorker.execute(workerRun(context), new ProcessExecutionRequest(
                command, resolved.workingDirectory(), Duration.ofSeconds(timeout), 60000), context.getCancellationToken()));
        recordVerification(context, classification.normalizedCommand().displayCommand(), effectiveStrategy, result);
        if (!result.isSuccess()) {
            String output = result.getContent() == null ? "" : result.getContent().replaceAll("\\s+", " ");
            log.warn("RUN_TESTS_FAILED taskId={} strategy={} command={} output={}",
                    context.getTaskId(), effectiveStrategy, classification.normalizedCommand().displayCommand(),
                    output.length() <= 500 ? output : output.substring(0, 500));
        }
        return result;
    }

    private void recordVerification(AgentContext context, String command, VerificationStrategy strategy, ToolResult result) {
        if (context.getTaskId() == null || verificationMapper == null || artifactService == null) return;
        try {
            AgentVerification verification = new AgentVerification();
            verification.setTaskId(context.getTaskId());
            verification.setChangeSetId(0L);
            verification.setStudentId(context.getStudentId());
            verification.setProjectId(context.getProject().getProjectId());
            verification.setCommand(command);
            verification.setStatus(result.isSuccess() ? "passed" : "failed");
            verification.setExitCode(result.isSuccess() ? 0 : 1);
            verification.setOutput(result.getContent());
            verification.setCreateTime(LocalDateTime.now());
            verificationMapper.insert(verification);
            artifactService.record(context.getTaskId(), "verification_log", null,
                    "strategy=" + strategy.name().toLowerCase() + "\n" + result.getContent());
        } catch (Exception ignored) {
            // 验证结果持久化失败不能掩盖真实命令结果。
        }
    }

    private WorkerRunSpec workerRun(AgentContext context) {
        String runId = context.getTaskId() == null ? "agent-" + context.getSessionId() : "task-" + context.getTaskId();
        return WorkerRunSpec.forWorkspace(runId, context.getWorkspaceRoot(), context.isNetworkEnabled());
    }
}