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
 *
 * <p>支持两种工具结果形态：</p>
 * <ul>
 *   <li>native 协议：{@code role=tool} 消息（{@code tool_call_id}/{@code name}/{@code content}）；
 *       只替换 content 为占位文本，保留协议字段以维持 assistant tool_calls 与 tool result 的
 *       一一配对，不破坏 {@code AgentProviderProtocolValidator} 校验。</li>
 *   <li>legacy 文本：工具结果伪装为 {@code [Tool xxx result]} 开头的 user 消息（历史 transcript 兼容）。</li>
 * </ul>
 */
public final class TurnAwareContextPruner {
    private static final Pattern TOOL_RESULT = Pattern.compile("^\\[Tool\\s+(.+?)\\s+result]", Pattern.CASE_INSENSITIVE);
    private static final String CLEARED_MARKER = "[Old tool result content cleared.";
    private static final Set<String> PROTECTED_TOOLS = Set.of(
            "write_file", "write", "edit_file", "edit", "apply_patch", "patch",
            "run_tests", "create_plan", "plan", "todo_write", "todowrite",
            "question", "permission");

    private final ToIntFunction<String> tokenEstimator;

    public TurnAwareContextPruner(ToIntFunction<String> tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 对标 OpenCode session/compaction.ts: preserveRecentBudget
     * 动态计算尾部保护预算：min(8000, max(2000, contextWindowTokens * 0.25))
     */
    public static int calculateTailTokenBudget(Integer contextWindowTokens) {
        int window = (contextWindowTokens != null && contextWindowTokens > 0) ? contextWindowTokens : 32_768;
        int quarter = (int) Math.floor(window * 0.25);
        return Math.min(8_000, Math.max(2_000, quarter));
    }

    public boolean hasPrunableHistoricalToolResult(List<Map<String, Object>> messages, int tailTurns, int tailTokenBudget) {
        int tailStart = selectTailStart(messages, tailTurns, tailTokenBudget);
        for (int index = 0; index < tailStart; index++) {
            if (eligibleToolNameOf(messages.get(index)) != null) {
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
            if (clearToolResultContent(messages, index)) {
                pruned++;
            }
        }
        int after = estimate(messages);
        return new Result(pruned > 0, before, after, tailStart, retainedTurnCount(messages, tailStart), pruned);
    }

    /**
     * 占位化列表中全部可裁剪的工具结果，无尾部保护（调用方保证传入的是待摘要/丢弃的 head）。
     * 用于压缩链路的 head 预处理：native role=tool 与 legacy 文本两种形态统一处理，
     * 让 LLM 摘要与确定性 checkpoint 的输入体积显著缩小。
     */
    public Result pruneAllEligible(List<Map<String, Object>> messages) {
        int before = estimate(messages);
        int pruned = 0;
        for (int index = 0; index < messages.size(); index++) {
            if (clearToolResultContent(messages, index)) {
                pruned++;
            }
        }
        int after = estimate(messages);
        return new Result(pruned > 0, before, after, 0, countUserTurns(messages), pruned);
    }

    private boolean clearToolResultContent(List<Map<String, Object>> messages, int index) {
        Map<String, Object> message = messages.get(index);
        if (message == null) {
            return false;
        }
        String toolName = eligibleToolNameOf(message);
        if (toolName == null) {
            return false;
        }
        String content = contentOf(message);
        String replacement = placeholder(toolName);
        if (replacement.length() >= content.length()) {
            return false;
        }
        Map<String, Object> rewritten = new java.util.LinkedHashMap<>();
        rewritten.putAll(message);
        rewritten.put("content", replacement);
        messages.set(index, rewritten);
        return true;
    }

    /**
     * 返回可裁剪的工具名；不可裁剪返回 null。native role=tool 以 {@code name} 字段为准，
     * legacy 文本以 {@code [Tool xxx result]} 前缀为准；错误结果与受保护工具一律豁免。
     */
    private String eligibleToolNameOf(Map<String, Object> message) {
        String content = contentOf(message);
        if ("tool".equalsIgnoreCase(roleOf(message))) {
            String toolName = normalizeTool(stringValue(message.get("name")));
            if (toolName.isEmpty()) {
                return null;
            }
            if (content.isBlank() || content.contains(CLEARED_MARKER) || containsErrorSignal(content)) {
                return null;
            }
            return PROTECTED_TOOLS.contains(toolName) ? null : toolName;
        }
        Matcher matcher = TOOL_RESULT.matcher(content);
        if (!matcher.find() || content.contains(CLEARED_MARKER) || containsErrorSignal(content)) {
            return null;
        }
        String toolName = normalizeTool(matcher.group(1));
        return PROTECTED_TOOLS.contains(toolName) ? null : toolName;
    }

    private boolean containsErrorSignal(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("error") || lower.contains("exception") || lower.contains("failed")
                || lower.contains("failure") || lower.contains("unresolved") || lower.contains("错误")
                || lower.contains("异常") || lower.contains("失败") || lower.contains("未解决");
    }

    private String placeholder(String toolName) {
        return "[Tool " + toolName + " result]\n[Old tool result content cleared. Re-run "
                + toolName + " if exact output is needed.]";
    }

    private int selectTailStart(List<Map<String, Object>> messages, int tailTurns, int tailTokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        List<Integer> starts = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            if (isRealUserTurn(messages.get(index))) {
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
            if (isRealUserTurn(messages.get(index))) {
                count++;
            }
        }
        return count;
    }

    private int countUserTurns(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> message : messages) {
            if (isRealUserTurn(message)) {
                count++;
            }
        }
        return count;
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

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeTool(String tool) {
        return tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isRealUserTurn(Map<String, Object> message) {
        if (!"user".equalsIgnoreCase(roleOf(message))) {
            return false;
        }
        Object content = message == null ? null : message.get("content");
        return !(content instanceof String text && text.stripLeading().startsWith("<agent_focus_anchor"));
    }

    public record Result(boolean changed, int tokensBefore, int tokensAfter, int tailStartIndex,
                         int retainedTurns, int prunedToolResults) {
    }
}
