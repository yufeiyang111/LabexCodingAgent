package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.TestCommandResolver;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.entity.AgentVerification;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.mapper.AgentVerificationMapper;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RunTestsTool
implements AgentTool {
    private final SandboxWorker sandboxWorker;
    private final CommandClassifier commandClassifier = new CommandClassifier();
    private final AgentVerificationMapper verificationMapper;
    private final AgentRunArtifactService artifactService;

    public RunTestsTool(SandboxWorker sandboxWorker) {
        this(sandboxWorker, null, null);
    }

    @Autowired
    public RunTestsTool(SandboxWorker sandboxWorker, AgentVerificationMapper verificationMapper, AgentRunArtifactService artifactService) {
        this.sandboxWorker = sandboxWorker;
        this.verificationMapper = verificationMapper;
        this.artifactService = artifactService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("run_tests").description("\u81ea\u52a8\u8bc6\u522b\u9879\u76ee\u7c7b\u578b\u5e76\u8fd0\u884c\u53d7\u9650\u7684\u9ed8\u8ba4\u6d4b\u8bd5\u547d\u4ee4\u3002\u652f\u6301 Maven/npm/pip/pytest \u7b49\u3002")
                .intProperty("timeout_seconds", "\u8d85\u65f6\u65f6\u95f4\uff08\u9ed8\u8ba4120\u79d2\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        int timeout = Math.min(600, Math.max(1, args.has("timeout_seconds") ? args.get("timeout_seconds").getAsInt() : 120));
        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(context.getWorkspaceRoot());
        List<String> command = resolved.command();
        if (command.isEmpty()) {
            return ToolResult.failed("\u65e0\u6cd5\u8bc6\u522b\u9879\u76ee\u7c7b\u578b\uff0crun_tests \u53ea\u8fd0\u884c\u53d7\u9650\u7684\u9ed8\u8ba4\u6d4b\u8bd5\u547d\u4ee4");
        }
        String canonicalCommand = String.join(" ", command);
        CommandClassification classification = commandClassifier.classify(new CommandRequest(
                canonicalCommand, "direct", ".", timeout, false, false, "agent-worker"));
        if (classification.decision() != CommandDecision.ALLOW) {
            if (classification.requiresApproval()) {
                return ToolResult.approvalRequired("test command requires a server-owned one-time approval", canonicalCommand);
            }
            return ToolResult.failed("command blocked by restricted command policy: "
                    + classification.reasonCode().name().toLowerCase());
        }
        ToolResult result = ToolResult.fromProcessExecution(sandboxWorker.execute(workerRun(context), new ProcessExecutionRequest(
                command,
                resolved.workingDirectory(),
                Duration.ofSeconds(timeout),
                60000), context.getCancellationToken()));
        recordVerification(context, classification.normalizedCommand().displayCommand(), result);
        return result;
    }

    private void recordVerification(AgentContext context, String command, ToolResult result) {
        if (context.getTaskId() == null || verificationMapper == null || artifactService == null) return;
        try {
            AgentVerification verification = new AgentVerification();
            verification.setTaskId(context.getTaskId()); verification.setChangeSetId(0L);
            verification.setStudentId(context.getStudentId()); verification.setProjectId(context.getProject().getProjectId());
            verification.setCommand(command); verification.setStatus(result.isSuccess() ? "passed" : "failed");
            verification.setExitCode(result.isSuccess() ? 0 : 1); verification.setOutput(result.getContent()); verification.setCreateTime(LocalDateTime.now());
            verificationMapper.insert(verification);
            artifactService.record(context.getTaskId(), "verification_log", null, result.getContent());
        } catch (Exception ignored) {
            // Verification persistence must not hide the actual test result.
        }
    }


    private WorkerRunSpec workerRun(AgentContext context) {
        String runId = context.getTaskId() == null
                ? "agent-" + context.getSessionId()
                : "task-" + context.getTaskId();
        return WorkerRunSpec.forWorkspace(runId, context.getWorkspaceRoot());
    }
}
