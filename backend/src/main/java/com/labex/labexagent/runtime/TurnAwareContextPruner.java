package com.labex.labexagent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clears only safe historical tool output from the in-memory provider request. It never mutates
 * persisted conversation messages, change records, or run logs.
 */
public final class TurnAwareContextPruner {
    private static final Pattern TOOL_RESULT = Pattern.compile("^\\[Tool\\s+(.+?)\\s+result]", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PROTECTED_TOOLS = Set.of(
            "write_file", "write", "edit_file", "edit", "apply_patch", "patch",
            "run_tests", "create_plan", "plan", "todo_write", "todowrite",
            "question", "permission");

    private final ToIntFunction<String> tokenEstimator;

    public TurnAwareContextPruner(ToIntFunction<String> tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    public boolean hasPrunableHistoricalToolResult(List<Map<String, Object>> messages, int tailTurns, int tailTokenBudget) {
        int tailStart = selectTailStart(messages, tailTurns, tailTokenBudget);
        for (int index = 0; index < tailStart; index++) {
            if (isEligibleToolResult(contentOf(messages.get(index)))) {
                return true;
            }
        }
        return false;
    }

    public Result prune(List<Map<String, Object>> messages, int tailTurns, int tailTokenBudget) {
        int before = estimate(messages);
        int tailStart = selectTailStart(messages, tailTurns, tailTokenBudget);
        int pruned = 0;
        for (int index = 0; index < tailStart; index++) {
            Map<String, Object> message = messages.get(index);
            String content = contentOf(message);
            if (!isEligibleToolResult(content)) {
                continue;
            }
            String toolName = toolName(content);
            String replacement = "[Tool " + toolName + " result]\n[Old tool result content cleared. Re-run "
                    + toolName + " if exact output is needed.]";
            if (replacement.length() >= content.length()) {
                continue;
            }            Map<String, Object> rewritten = new java.util.LinkedHashMap<>();
            if (message != null) {
                rewritten.putAll(message);
            }
            rewritten.put("content", replacement);
            messages.set(index, rewritten);
            pruned++;
        }
        int after = estimate(messages);
        return new Result(pruned > 0, before, after, tailStart, retainedTurnCount(messages, tailStart), pruned);
    }

    private int selectTailStart(List<Map<String, Object>> messages, int tailTurns, int tailTokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        List<Integer> starts = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            if ("user".equalsIgnoreCase(roleOf(messages.get(index)))) {
                starts.add(index);
            }
        }
        if (starts.isEmpty()) {
            return Math.max(0, messages.size() - 1);
        }
        int startIndex = Math.max(0, starts.size() - Math.max(1, tailTurns));
        int tailStart = starts.get(startIndex);
        while (tailStart < messages.size() && estimate(messages.subList(tailStart, messages.size())) > tailTokenBudget) {
            int nextStart = nextTurnStart(starts, tailStart);
            if (nextStart < 0) {
                return tailStart;
            }
            tailStart = nextStart;
        }
        return tailStart;
    }

    private int nextTurnStart(List<Integer> starts, int current) {
        for (int start : starts) {
            if (start > current) {
                return start;
            }
        }
        return -1;
    }

    private int retainedTurnCount(List<Map<String, Object>> messages, int tailStart) {
        int count = 0;
        for (int index = tailStart; index < messages.size(); index++) {
            if ("user".equalsIgnoreCase(roleOf(messages.get(index)))) {
                count++;
            }
        }
        return count;
    }

    private boolean isEligibleToolResult(String content) {
        Matcher matcher = TOOL_RESULT.matcher(content);
        if (!matcher.find() || content.contains("[Old tool result content cleared]")) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || lower.contains("exception") || lower.contains("failed")
                || lower.contains("failure") || lower.contains("unresolved") || lower.contains("错误")
                || lower.contains("异常") || lower.contains("失败") || lower.contains("未解决")) {
            return false;
        }
        return !PROTECTED_TOOLS.contains(normalizeTool(matcher.group(1)));
    }

    private String toolName(String content) {
        Matcher matcher = TOOL_RESULT.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : "tool";
    }

    private String normalizeTool(String tool) {
        return tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
    }

    private int estimate(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> message : messages) {
            total += tokenEstimator.applyAsInt(contentOf(message));
        }
        return total;
    }

    private String contentOf(Map<String, Object> message) {
        if (message == null) {
            return "";
        }
        Object content = message.get("content");
        return content instanceof String value ? value : "";
    }

    private String roleOf(Map<String, Object> message) {
        Object role = message == null ? null : message.get("role");
        return role == null ? "user" : String.valueOf(role);
    }

    public record Result(boolean changed, int tokensBefore, int tokensAfter, int tailStartIndex,
                         int retainedTurns, int prunedToolResults) {
    }
}