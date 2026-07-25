package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class ExecuteCodeTool implements AgentTool {
    /**
     * execute_code is for sandboxed calculations, not a second shell or network escape hatch.
     * Sandbox isolation remains the authoritative boundary; this guard prevents obvious policy bypasses.
     */
    private static final Pattern RESTRICTED_EXECUTION_CAPABILITY = Pattern.compile(
            "(?is)(?:\\bchild_process\\b|\\bexecsync\\s*\\(|\\bspawn(?:sync)?\\s*\\(|"
                    + "\\bsubprocess\\b|\\bos\\.system\\s*\\(|\\bshell\\s*=\\s*true|"
                    + "\\bdeno\\.command\\b|\\bbun\\.spawn\\b|"
                    + "\\b(?:require|import)\\s*\\(?.{0,40}\\b(?:net|http|https|tls|socket)\\b)");

    private final SandboxWorker sandboxWorker;

    public ExecuteCodeTool(SandboxWorker sandboxWorker) {
        this.sandboxWorker = sandboxWorker;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
            .name("execute_code").description("Execute Python/JavaScript code in sandbox.")
            .stringProperty("language", "Programming language: python or javascript", true)
            .stringProperty("code", "Code to execute", true)
            .intProperty("timeout_seconds", "Timeout in seconds (default 30)", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String language = args.has("language") ? args.get("language").getAsString() : "";
        String code = args.has("code") ? args.get("code").getAsString() : "";
        if (language.isEmpty()) return ToolResult.failed("language is required");
        if (code.isEmpty()) return ToolResult.failed("code is required");
        if (RESTRICTED_EXECUTION_CAPABILITY.matcher(code).find()) {
            return ToolResult.failed("execute_code contains restricted execution capability; use run_tests or an approved managed command instead");
        }
        int timeout = Math.min(120, Math.max(1, args.has("timeout_seconds") ? args.get("timeout_seconds").getAsInt() : 30));
        String extension = language.equalsIgnoreCase("python") ? ".py" : ".js";
        String command = language.equalsIgnoreCase("python") ? "python3" : "node";
        Path tempFile = context.getWorkspaceRoot().resolve(".labex-agent" + File.separator + "temp" + System.currentTimeMillis() + extension);
        WorkerRunSpec workerRun = workerRun(context);
        sandboxWorker.applyChange(workerRun, context.getWorkspaceRoot().relativize(tempFile).toString(), code);
        String scriptArgument = sandboxWorker.usesLinuxShell()
                ? "/workspace/" + context.getWorkspaceRoot().relativize(tempFile).toString().replace('\\', '/')
                : tempFile.toString();
        List<String> cmd = List.of(command, scriptArgument);
        try {
            return ToolResult.fromProcessExecution(sandboxWorker.execute(workerRun, new ProcessExecutionRequest(
                    cmd,
                    context.getWorkspaceRoot(),
                    Duration.ofSeconds(timeout),
                    60000), context.getCancellationToken()));
        } finally { try { Files.deleteIfExists(tempFile); } catch (Exception e) {} }
    }

    private WorkerRunSpec workerRun(AgentContext context) {
        String runId = context.getTaskId() == null
                ? "agent-" + context.getSessionId()
                : "task-" + context.getTaskId();
        return WorkerRunSpec.forWorkspace(runId, context.getWorkspaceRoot());
    }
}
