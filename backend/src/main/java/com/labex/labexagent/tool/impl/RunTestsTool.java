package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
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
public class RunTestsTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("run_tests").description("\u81ea\u52a8\u8bc6\u522b\u9879\u76ee\u7c7b\u578b\u5e76\u8fd0\u884c\u6d4b\u8bd5\u547d\u4ee4\u3002\u652f\u6301 Maven/npm/pip/pytest \u7b49\u3002").stringProperty("command", "\u81ea\u5b9a\u4e49\u6d4b\u8bd5\u547d\u4ee4\uff08\u53ef\u9009\uff09", false).intProperty("timeout_seconds", "\u8d85\u65f6\u65f6\u95f4\uff08\u9ed8\u8ba4120\u79d2\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String command = args.has("command") ? args.get("command").getAsString() : "";
        int timeout = Math.min(600, Math.max(1, args.has("timeout_seconds") ? args.get("timeout_seconds").getAsInt() : 120));
        if (command.isEmpty()) {
            command = this.detectTestCommand(context.getWorkspaceRoot());
        }
        if (command.isEmpty()) {
            return ToolResult.failed("\u65e0\u6cd5\u8bc6\u522b\u9879\u76ee\u7c7b\u578b\uff0c\u8bf7\u624b\u52a8\u6307\u5b9a\u6d4b\u8bd5\u547d\u4ee4");
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = windows ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-lc", command);
        Process process = new ProcessBuilder(cmd).directory(context.getWorkspaceRoot().toFile()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.failed("\u6d4b\u8bd5\u8d85\u65f6\uff0c\u5df2\u7ec8\u6b62");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return ToolResult.ok((String)("exit=" + process.exitValue() + "\n" + ToolSupport.limit((String)output, 60000)));
    }

    private String detectTestCommand(Path root) {
        if (Files.exists(root.resolve("pom.xml"), new LinkOption[0])) {
            return "mvn test";
        }
        if (Files.exists(root.resolve("package.json"), new LinkOption[0])) {
            return "npm test";
        }
        if (Files.exists(root.resolve("build.gradle"), new LinkOption[0])) {
            return "./gradlew test";
        }
        if (Files.exists(root.resolve("requirements.txt"), new LinkOption[0]) || Files.exists(root.resolve("pyproject.toml"), new LinkOption[0])) {
            return "python -m pytest";
        }
        if (Files.exists(root.resolve("pytest.ini"), new LinkOption[0])) {
            return "pytest";
        }
        return "";
    }
}

