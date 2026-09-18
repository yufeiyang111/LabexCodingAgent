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
    /**
     * 对齐 OpenCode {@code session/compaction.ts} 的 {@code PRUNE_PROTECT}：从最新往前累计可裁剪工具
     * 输出的 token，落在该预算内的结果即使可裁剪也一律保护——最近使用过的上下文清掉代价最高。
     */
    public static final int DEFAULT_PRUNE_PROTECT_TOKENS = 40_000;
    /**
     * 对齐 OpenCode {@code session/compaction.ts} 的 {@code PRUNE_MINIMUM}：净可回收 token 不足该阈值
     * 时放弃整次 prune。prune 是有损操作，为微弱收益破坏上下文不划算。
     */
    public static final int DEFAULT_PRUNE_MINIMUM_TOKENS = 20_000;
    /**
     * 受保护工具：变更类（write/edit/apply_patch）是 ChangeSet 与 diff 的证据链，规划与交互类承载
     * 任务意图，{@code skill} 输出是操作手册/规范原文——被占位化后都无法从原始数据重建（不像 shell
     * 输出可以重跑），因此一律不占位化。{@code skill} 对齐 OpenCode {@code PRUNE_PROTECTED_TOOLS}。
     */
    private static final Set<String> PROTECTED_TOOLS = Set.of(
            "write_file", "write", "edit_file", "edit", "apply_patch", "patch",
            "run_tests", "create_plan", "plan", "todo_write", "todowrite",
            "question", "permission",
            "skill");

    private final ToIntFunction<String> tokenEstimator;
    private final int pruneProtectTokens;
    private final int pruneMinimumTokens;

    public TurnAwareContextPruner(ToIntFunction<String> tokenEstimator) {
        this(tokenEstimator, DEFAULT_PRUNE_PROTECT_TOKENS, DEFAULT_PRUNE_MINIMUM_TOKENS);
    }

    public TurnAwareContextPruner(ToIntFunction<String> tokenEstimator, int pruneProtectTokens, int pruneMinimumTokens) {
        this.tokenEstimator = tokenEstimator;
        this.pruneProtectTokens = Math.max(0, pruneProtectTokens);
        this.pruneMinimumTokens = Math.max(0, pruneMinimumTokens);
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

    /**
     * 只读探测：安全尾部之外是否还存在可裁剪的工具结果。
     *
     * <p>这是"候选存在性"判断，<b>不</b>套用 {@code pruneProtectTokens} / {@code pruneMinimumTokens}
     * ——它只回答"是否值得走裁剪路径"（供 {@link ContextWindowSupervisor} 选择动作），不回答"最终裁多少"。
     * 真正的占位化决策由 {@link #selectPrunableIndexes} 给出，后者会因为最近输出落在保护额度内、
     * 或净可回收量不足门槛而返回空集。</p>
     */
    public boolean hasPrunableHistoricalToolResult(List<Map<String, Object>> messages, int tailTurns, int tailTokenBudget) {
        int tailStart = selectTailStart(messages, tailTurns, tailTokenBudget);
        for (int index = 0; index < tailStart; index++) {
            if (eligibleToolNameOf(messages.get(index)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回可安全占位化的消息下标（<b>不修改入参</b>）。这是生产占位化的唯一判据入口：
     * 调用方（Provider 投影边界）按返回下标替换工具输出文本，因此这里必须保持只读语义，
     * 让"选谁"与"怎么写"分离。
     *
     * <p>判据与 {@link #pruneAllEligible} 共享同一份区间判定与保护/门槛逻辑，不存在第二份阈值。</p>
     */
    public List<Integer> selectPrunableIndexes(List<Map<String, Object>> messages, int tailTurns, int tailTokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int tailStart = selectTailStart(messages, tailTurns, tailTokenBudget);
        return List.copyOf(pruneCandidates(messages, tailStart));
    }

    /**
     * 统一占位文本。压缩 head 预处理与 Provider 投影边界的占位化共用这一个生成点，
     * 避免两处格式漂移导致 {@link #CLEARED_MARKER} 检测失效（检测失效会让同一结果被反复占位化）。
     */
    public static String placeholderFor(String toolName) {
        String safe = toolName == null || toolName.isBlank() ? "tool" : toolName;
        return "[Tool " + safe + " result]\n[Old tool result content cleared. Re-run "
                + safe + " if exact output is needed.]";
    }

    /**
     * 计算本次可安全占位化的工具结果下标，对齐 OpenCode {@code session/compaction.ts} 的 prune：
     * 从 {@code tailStart} 往前扫描可裁剪结果并累计 token，最近 {@code pruneProtectTokens} 一律跳过；
     * 超出保护额度后的结果才是候选，且只有净可回收 token 大于 {@code pruneMinimumTokens} 时本次才动手，
     * 否则返回空集——不为微弱收益破坏上下文。
     */
    private List<Integer> pruneCandidates(List<Map<String, Object>> messages, int tailStart) {
        List<Integer> candidates = new ArrayList<>();
        int runningTokens = 0;
        int reclaimableTokens = 0;
        for (int index = tailStart - 1; index >= 0; index--) {
            Map<String, Object> message = messages.get(index);
            String toolName = eligibleToolNameOf(message);
            if (toolName == null) {
                continue;
            }
            String content = contentOf(message);
            String replacement = placeholder(toolName);
            if (replacement.length() >= content.length()) {
                continue;
            }
            int contentTokens = tokenEstimator.applyAsInt(content);
            runningTokens += contentTokens;
            if (runningTokens <= pruneProtectTokens) {
                continue;
            }
            candidates.add(index);
            reclaimableTokens += Math.max(0, contentTokens - tokenEstimator.applyAsInt(replacement));
        }
        if (reclaimableTokens <= pruneMinimumTokens) {
            return List.of();
        }
        return candidates;
    }

    /**
     * 占位化列表中全部可裁剪的工具结果，无尾部保护（调用方保证传入的是待摘要/丢弃的 head）。
     * 用于压缩链路的 head 预处理：native role=tool 与 legacy 文本两种形态统一处理，
     * 让 LLM 摘要与确定性 checkpoint 的输入体积显著缩小。
     *
     * <p>这里刻意不套用 {@code pruneProtectTokens} / {@code pruneMinimumTokens}：head 无论如何都会被
     * checkpoint 取代而不进入 provider 上下文，缩小摘要输入永远有利，不存在"为省一点点而破坏上下文"
     * 的场景；这两个门槛只对保留在上下文里的尾部保护式 prune 有意义。</p>
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
        return new Result(pruned > 0, before, after, pruned);
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
        return placeholderFor(toolName);
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

    public record Result(boolean changed, int tokensBefore, int tokensAfter, int prunedToolResults) {
    }
}
