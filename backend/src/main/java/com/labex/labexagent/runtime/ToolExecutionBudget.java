package com.labex.labexagent.runtime;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ShellCommandTimeouts;
import com.labex.labexagent.tool.ToolSupport;
import java.util.Locale;

/**
 * Agent 工具 watchdog 的预算计算。
 * 普通命令使用默认 timeout，Shell 根据命令类别为安装、构建、测试设置更长预算。
 */
public final class ToolExecutionBudget {
    private static final long READ_ONLY_MS = 30_000L;
    private static final long REMOTE_OR_LSP_MS = 90_000L;
    private static final long REPOSITORY_OPERATION_MS = 180_000L;
    private static final long DEFAULT_MS = 60_000L;
    private static final long COMMAND_MARGIN_MS = 5_000L;
    private static final long MAX_COMMAND_MS = 605_000L;

    private ToolExecutionBudget() {
    }

    public static long timeoutMs(String toolName, JsonObject arguments) {
        return timeoutMs(toolName, arguments, 900_000L);
    }

    public static long timeoutMs(String toolName, JsonObject arguments, long subagentTimeoutMs) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (isBlockingWaitTool(normalized)) {
            return Math.max(60_000L, Math.min(3_600_000L, subagentTimeoutMs));
        }
        if (isCommandTool(normalized)) {
            long requestedMs = commandTimeoutMs(normalized, arguments);
            return Math.min(MAX_COMMAND_MS, requestedMs + COMMAND_MARGIN_MS);
        }
        if (isReadOnlyTool(normalized)) return READ_ONLY_MS;
        if (isRemoteOrLspTool(normalized)) return REMOTE_OR_LSP_MS;
        if ("repo_clone".equals(normalized) || "repo_map".equals(normalized)) return REPOSITORY_OPERATION_MS;
        return DEFAULT_MS;
    }

    /**
     * 等待型工具判定（单一事实源）：task / subagent 会在执行线程上长时间阻塞等待
     * 子代理终态（上限 subagent.task-timeout-ms）。批处理器据此把这类调用放入
     * 独立等待线程池，避免占满普通工具线程导致其余工具排队。
     */
    public static boolean isBlockingWaitTool(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        return "task".equals(normalized) || "subagent".equals(normalized);
    }

    private static long commandTimeoutMs(String toolName, JsonObject arguments) {
        int defaultSeconds = "run_tests".equals(toolName) ? 120 : 60;
        boolean shellTool = "shell".equals(toolName) || "bash".equals(toolName);
        if (shellTool && hasValue(arguments, "timeout")) {
            return Math.min(600_000L,
                    Math.max(1L, ToolSupport.intArg(arguments, "timeout", defaultSeconds * 1_000)));
        }
        if (hasValue(arguments, "timeout_seconds")) {
            int seconds = Math.min(600,
                    Math.max(1, ToolSupport.intArg(arguments, "timeout_seconds", defaultSeconds)));
            return seconds * 1_000L;
        }
        if (shellTool) {
            String command = ToolSupport.stringArgMulti(arguments, "", "command", "cmd", "shell_command");
            return ShellCommandTimeouts.defaultTimeoutMs(command);
        }
        return defaultSeconds * 1_000L;
    }

    private static boolean hasValue(JsonObject arguments, String field) {
        return arguments != null && arguments.has(field) && !arguments.get(field).isJsonNull();
    }

    static boolean isCommandTool(String toolName) {
        return "shell".equals(toolName) || "bash".equals(toolName)
                || "run_tests".equals(toolName) || "execute_code".equals(toolName);
    }

    private static boolean isReadOnlyTool(String toolName) {
        return "read_file".equals(toolName) || "list_files".equals(toolName)
                || "glob".equals(toolName) || "grep".equals(toolName)
                || "search_code".equals(toolName) || "diagnostics".equals(toolName)
                || "retrieve_context".equals(toolName);
    }

    private static boolean isRemoteOrLspTool(String toolName) {
        return "mcp_call".equals(toolName) || "web_search".equals(toolName)
                || "web_fetch".equals(toolName) || "lsp".equals(toolName)
                || "lsp_symbols".equals(toolName) || "understand_image".equals(toolName);
    }
}
