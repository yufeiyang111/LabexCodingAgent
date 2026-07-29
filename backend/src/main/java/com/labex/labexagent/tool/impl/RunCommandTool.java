package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.commandsecurity.CommandPolicyMessage;
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
import org.springframework.stereotype.Component;

@Component
public class RunCommandTool
implements AgentTool {
    private final SandboxWorker sandboxWorker;
    private final CommandClassifier commandClassifier = new CommandClassifier();

    public RunCommandTool(SandboxWorker sandboxWorker) {
        this.sandboxWorker = sandboxWorker;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("shell").description("\u6267\u884c\u4e00\u6761\u53d7\u9650\u76f4\u63a5\u547d\u4ee4\uff1b\u4e0d\u652f\u6301\u7ba1\u9053\u3001\u91cd\u5b9a\u5411\u3001\u53d8\u91cf\u3001\u547d\u4ee4\u66ff\u6362\u3001\u5f15\u53f7\u548c\u591a\u547d\u4ee4\u4e32\u8054\u3002\u5371\u9669\u547d\u4ee4\u9700\u8981\u7528\u6237\u786e\u8ba4\u3002").stringProperty("command", "\u8981\u6267\u884c\u7684\u76f4\u63a5\u547d\u4ee4", true).stringProperty("working_directory", "\u5de5\u4f5c\u76ee\u5f55\uff08\u76f8\u5bf9\u4e8e\u9879\u76ee\u6839\u76ee\u5f55\uff09", false).intProperty("timeout_seconds", "\u8d85\u65f6\u65f6\u95f4\uff08\u9ed8\u8ba460\u79d2\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String command = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"command", "cmd", "shell_command"});
        if (command.isEmpty()) {
            return ToolResult.failed("command is required");
        }
        CommandClassification classification = commandClassifier.classify(new CommandRequest(command));
        if (classification.decision() != CommandDecision.ALLOW) {
            if (classification.requiresApproval()) {
                return ToolResult.approvalRequired("command requires a server-owned one-time approval", command);
            }
            return ToolResult.failed("command blocked by restricted command policy\n"
                    + "reason=" + classification.reasonCode().name().toLowerCase() + "\n"
                    + "hint=" + CommandPolicyMessage.forReason(classification.reasonCode()));
        }
        String workingDirectory = ToolSupport.stringArgMulti(args, "", "working_directory", "workingDirectory", "cwd");
        Path workingPath = context.getWorkspaceRoot();
        if (!workingDirectory.isBlank()) {
            try {
                workingPath = ToolSupport.resolve(context, workingDirectory);
            } catch (IllegalArgumentException exception) {
                return ToolResult.failed("Unsafe working directory");
            }
            if (!Files.isDirectory(workingPath, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.failed("working_directory must be an existing directory");
            }
        }
        int timeout = Math.min(600, Math.max(1, args.has("timeout_seconds") ? args.get("timeout_seconds").getAsInt() : 60));
        List<String> cmd;
        try {
            cmd = DirectCommandTokenizer.tokenize(classification.normalizedCommand().canonicalCommand());
        } catch (IllegalArgumentException exception) {
            return ToolResult.failed(exception.getMessage());
        }
        return ToolResult.fromProcessExecution(sandboxWorker.execute(workerRun(context), new ProcessExecutionRequest(
                cmd,
                workingPath,
                Duration.ofSeconds(timeout),
                60000), context.getCancellationToken()));
    }

    private WorkerRunSpec workerRun(AgentContext context) {
        String runId = context.getTaskId() == null
                ? "agent-" + context.getSessionId()
                : "task-" + context.getTaskId();
        return WorkerRunSpec.forWorkspace(runId, context.getWorkspaceRoot());
    }
}
