package com.labex.labexagent.execution;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 构建、依赖安装和测试命令允许更长时间，其余命令使用保守默认值。
 * 命令类别由超时策略统一决定，避免各个调用方重复实现。
 */
public final class ShellCommandTimeouts {
    public static final int DEFAULT_TIMEOUT_MS = 60_000;
    public static final int NODE_TIMEOUT_MS = 240_000;
    public static final int PYTHON_TIMEOUT_MS = 180_000;
    public static final int BUILD_TIMEOUT_MS = 300_000;

    private static final Pattern BUILD_TOOL = Pattern.compile(
            "(?<![a-z0-9_.-])(?:mvn|mvnw|gradle|gradlew)(?![a-z0-9_.-])");
    private static final Pattern NODE_TOOL = Pattern.compile(
            "(?<![a-z0-9_.-])(?:npm|npx|pnpm|yarn|bun)(?![a-z0-9_.-])");
    private static final Pattern PYTHON_TOOL = Pattern.compile(
            "(?<![a-z0-9_.-])(?:python|python3|pytest)(?![a-z0-9_.-])");

    private ShellCommandTimeouts() {
    }

    public static int defaultTimeoutMs(String command) {
        String normalized = command == null ? "" : command.toLowerCase(Locale.ROOT);
        if (BUILD_TOOL.matcher(normalized).find()) {
            return BUILD_TIMEOUT_MS;
        }
        if (NODE_TOOL.matcher(normalized).find()) {
            return NODE_TIMEOUT_MS;
        }
        if (PYTHON_TOOL.matcher(normalized).find()) {
            return PYTHON_TIMEOUT_MS;
        }
        return DEFAULT_TIMEOUT_MS;
    }
}
