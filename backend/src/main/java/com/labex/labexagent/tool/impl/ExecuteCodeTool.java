package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ExecuteCodeTool implements AgentTool {
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
        int timeout = Math.min(120, Math.max(1, args.has("timeout_seconds") ? args.get("timeout_seconds").getAsInt() : 30));
        String extension = language.equalsIgnoreCase("python") ? ".py" : ".js";
        String command = language.equalsIgnoreCase("python") ? "python3" : "node";
        Path tempFile = context.getWorkspaceRoot().resolve(".labex-agent" + File.separator + "temp" + System.currentTimeMillis() + extension);
        Files.createDirectories(tempFile.getParent());
        Files.writeString(tempFile, code);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = windows ? List.of("cmd.exe", "/c", command + " " + tempFile.toString()) : List.of("/bin/sh", "-c", command + " " + tempFile.toString());
        try {
            Process process = new ProcessBuilder(cmd).directory(context.getWorkspaceRoot().toFile()).redirectErrorStream(true).start();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) { process.destroyForcibly(); return ToolResult.failed("Code execution timeout"); }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return ToolResult.ok("exit=" + process.exitValue() + "\n" + ToolSupport.limit(output, 60000));
        } finally { try { Files.deleteIfExists(tempFile); } catch (Exception e) {} }
    }
}