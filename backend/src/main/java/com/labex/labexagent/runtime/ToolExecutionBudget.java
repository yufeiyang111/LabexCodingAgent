package com.labex.labexagent.runtime;

import com.google.gson.JsonObject;
import com.labex.labexagent.tool.ToolSupport;
import java.util.Locale;

/**
 * Central watchdog policy for Agent tools. Command tools keep their own explicit
 * timeout_seconds value; this policy only adds a bounded cleanup margin so that
 * one tool cannot block the Agent loop indefinitely.
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
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        if (isCommandTool(normalized)) {
            int defaultSeconds = "run_tests".equals(normalized) ? 120 : 60;
            int seconds = Math.min(600, Math.max(1, ToolSupport.intArg(arguments, "timeout_seconds", defaultSeconds)));
            return Math.min(MAX_COMMAND_MS, seconds * 1_000L + COMMAND_MARGIN_MS);
        }
        if (isReadOnlyTool(normalized)) return READ_ONLY_MS;
        if (isRemoteOrLspTool(normalized)) return REMOTE_OR_LSP_MS;
        if ("repo_clone".equals(normalized) || "repo_map".equals(normalized)) return REPOSITORY_OPERATION_MS;
        return DEFAULT_MS;
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
