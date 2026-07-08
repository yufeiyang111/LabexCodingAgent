package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.controller.student.ProjectCommandSafety;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class RunCommandTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("shell").description("\u6267\u884cshell\u547d\u4ee4\uff08\u5982\u8fd0\u884c\u6d4b\u8bd5\u3001git\u64cd\u4f5c\u3001\u5b89\u88c5\u4f9d\u8d56\u7b49\uff09\u3002\u5371\u9669\u547d\u4ee4\u9700\u8981\u7528\u6237\u786e\u8ba4\u3002").stringProperty("command", "\u8981\u6267\u884c\u7684shell\u547d\u4ee4", true).intProperty("timeout_seconds", "\u8d85\u65f6\u65f6\u95f4\uff08\u9ed8\u8ba460\u79d2\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String command = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"command", "cmd", "shell_command"});
        if (command.isEmpty()) {
            return ToolResult.failed("command is required");
        }
        boolean allowDangerous = args.has("allow_dangerous") && args.get("allow_dangerous").getAsBoolean();
        ProjectCommandSafety.SafetyCheck safety = ProjectCommandSafety.check((String)command, (boolean)allowDangerous);
        if (!safety.allowed()) {
            if (safety.approvalRequired()) {
                return ToolResult.approvalRequired((String)(safety.message() + "\n\u547d\u4ee4: " + command), (String)command);
            }
            return ToolResult.failed((String)safety.message());
        }
        int timeout = Math.min(600, Math.max(1, args.has("timeout_seconds") ? args.get("timeout_seconds").getAsInt() : 60));
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = windows ? List.of("cmd.exe", "/c", command) : this.unixShell(command);
        Process process = new ProcessBuilder(cmd).directory(context.getWorkspaceRoot().toFile()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.failed((String)("\u547d\u4ee4\u8d85\u65f6\uff0c\u5df2\u7ec8\u6b62: " + command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return ToolResult.ok((String)("exit=" + process.exitValue() + "\n" + ToolSupport.limit((String)output, 60000)));
    }

    private List<String> unixShell(String command) {
        if (Files.exists(Path.of("/bin/bash", new String[0]), new LinkOption[0])) {
            return List.of("/bin/bash", "-lc", command);
        }
        return List.of("/bin/sh", "-lc", command);
    }
}

