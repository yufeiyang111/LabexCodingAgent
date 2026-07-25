package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.DirectCommandTokenizer;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.runtime.AgentContext;
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

// 已禁用：与 RunCommandTool (shell) 功能重复，保留代码供 shell 工具内部使用
// @Component
public class BashTool
implements AgentTool {
    private final SandboxWorker sandboxWorker;
    private final CommandClassifier commandClassifier = new CommandClassifier();

    public BashTool(SandboxWorker sandboxWorker) {
        this.sandboxWorker = sandboxWorker;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("bash").description("Shell command alias. Execute a command in the student workspace; dangerous commands require approval.").stringProperty("command", "command to execute", true).intProperty("timeout_seconds", "timeout seconds, default 60", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String command = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"command", "cmd"});
        if (command.isBlank()) {
            return ToolResult.failed("command is required");
        }
        CommandClassification classification = commandClassifier.classify(new CommandRequest(command));
        if (classification.decision() != CommandDecision.ALLOW) {
            if (classification.requiresApproval()) {
                return ToolResult.approvalRequired("command requires a server-owned one-time approval", command);
            }
            return ToolResult.failed("command blocked by restricted command policy: " + classification.reasonCode().name().toLowerCase());
        }
        int timeout = Math.min(600, Math.max(1, ToolSupport.intArg((JsonObject)args, "timeout_seconds", 60)));
        List<String> cmd;
        try {
            cmd = DirectCommandTokenizer.tokenize(classification.normalizedCommand().canonicalCommand());
        } catch (IllegalArgumentException exception) {
            return ToolResult.failed(exception.getMessage());
        }
        return ToolResult.fromProcessExecution(sandboxWorker.execute(workerRun(context), new ProcessExecutionRequest(
                cmd,
                context.getWorkspaceRoot(),
                Duration.ofSeconds(timeout),
                30000), context.getCancellationToken()));
    }

    private WorkerRunSpec workerRun(AgentContext context) {
        String runId = context.getTaskId() == null
                ? "agent-" + context.getSessionId()
                : "task-" + context.getTaskId();
        return WorkerRunSpec.forWorkspace(runId, context.getWorkspaceRoot());
    }
}
