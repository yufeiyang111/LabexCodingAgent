package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 判据测试。生产占位化走 {@code AgentTranscriptProjectionService} 的投影边界，它调用
 * {@link TurnAwareContextPruner#selectPrunableIndexes}；压缩 head 预处理调用
 * {@link TurnAwareContextPruner#pruneAllEligible}。因此这里只针对这两个有生产调用方的入口断言，
 * 不再存在"测试保护一个无人调用的方法"的情况。
 */
class TurnAwareContextPrunerTest {

    /** 机制类用例用显式小阈值；生产默认值 40k/20k 会把测试里的候选全部挡在保护区内。 */
    private static final int PROTECT = 2_000;
    private static final int MINIMUM = 1_000;

    private final TurnAwareContextPruner pruner = new TurnAwareContextPruner(String::length, PROTECT, MINIMUM);

    @Test
    void calculateTailTokenBudgetAdaptsDynamicallyToContextWindow() {
        // 1. 小窗口模型（如 4k tokens）: 4000 * 0.25 = 1000 -> 触发下限保底 2000
        assertEquals(2_000, TurnAwareContextPruner.calculateTailTokenBudget(4_000));

        // 2. 标准 32k 模型: 32768 * 0.25 = 8192 -> 触发上限保护 8000
        assertEquals(8_000, TurnAwareContextPruner.calculateTailTokenBudget(32_768));

        // 3. 中等 16k 模型: 16000 * 0.25 = 4000
        assertEquals(4_000, TurnAwareContextPruner.calculateTailTokenBudget(16_000));

        // 4. 超大 128k/1M 模型: 截断为上限 8000
        assertEquals(8_000, TurnAwareContextPruner.calculateTailTokenBudget(128_000));

        // 5. null/0 默认回退 32k
        assertEquals(8_000, TurnAwareContextPruner.calculateTailTokenBudget(null));
        assertEquals(8_000, TurnAwareContextPruner.calculateTailTokenBudget(0));
    }

    @Test
    void selectsOnlyEligibleHistoricalToolResultsAndProtectsTheRecentTurns() {
        List<Map<String, Object>> messages = messages();
        List<Map<String, Object>> untouchedSnapshot = List.copyOf(messages);

        List<Integer> selected = pruner.selectPrunableIndexes(messages, 2, 4_000);

        // 只有最旧的 grep 结果超出门槛：write_file / run_tests 属受保护工具，最近的 read_file 落在安全尾部。
        assertEquals(List.of(4), selected);
        // 判据入口必须只读：入参一个字节都不能被改动，否则"选谁"与"怎么写"就耦合了。
        assertEquals(untouchedSnapshot, messages);
    }

    @Test
    void hasPrunableHistoricalToolResultIgnoresTheProtectAndMinimumThresholds() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("grep", "match\n" + "g".repeat(400))));
        messages.add(message("assistant", ""));
        messages.add(message("user", "recent"));

        // 探测只回答"有没有候选"，所以为真……
        assertTrue(pruner.hasPrunableHistoricalToolResult(messages, 1, 500));
        // ……而净可回收量远低于门槛时，真正的占位化决策会放弃动手。
        assertTrue(new TurnAwareContextPruner(String::length, 0, 10_000)
                .selectPrunableIndexes(messages, 1, 500).isEmpty());
    }

    @Test
    void pruneAllEligibleReplacesEveryEligibleToolResultIncludingLegacyText() {
        List<Map<String, Object>> head = new ArrayList<>();
        head.add(nativeToolMessage("call-read", "read_file", "file body\n" + "r".repeat(3_000)));
        head.add(message("assistant", ""));
        head.add(message("user", "[Tool grep result]\nmatch\n" + "g".repeat(3_000)));
        head.add(message("assistant", ""));

        TurnAwareContextPruner.Result result = pruner.pruneAllEligible(head);

        assertTrue(result.changed());
        assertEquals(2, result.prunedToolResults());
        assertTrue(result.tokensAfter() < result.tokensBefore());
        assertTrue(content(head.get(0)).contains("[Old tool result content cleared."));
        assertEquals("tool", head.get(0).get("role"));
        assertEquals("call-read", head.get(0).get("tool_call_id"));
        assertTrue(content(head.get(2)).contains("[Old tool result content cleared."));
        assertEquals("user", head.get(2).get("role"));
    }

    @Test
    void clearsNativeToolMessagesWithoutDisguisedPrefixAndPreservesProtocolFields() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(message("assistant", ""));
        messages.add(nativeToolMessage("call-grep", "grep", "match\n" + "g".repeat(4_000)));
        messages.add(message("assistant", ""));
        messages.add(message("user", "recent"));

        assertTrue(pruner.hasPrunableHistoricalToolResult(messages, 1, 2_000));

        TurnAwareContextPruner.Result result = pruner.pruneAllEligible(messages);

        assertTrue(result.changed());
        assertEquals(1, result.prunedToolResults());
        Map<String, Object> cleared = messages.get(2);
        // 协议字段必须原样保留、只替换 content，否则 assistant tool_calls 与 tool result 会失配。
        assertEquals("tool", cleared.get("role"));
        assertEquals("call-grep", cleared.get("tool_call_id"));
        assertEquals("grep", cleared.get("name"));
        assertEquals(Map.of("turn", 1), cleared.get("metadata"));
        String content = content(cleared);
        assertTrue(content.contains("[Old tool result content cleared."));
        assertTrue(content.startsWith("[Tool grep result]"));
        assertFalse(content.contains("g".repeat(100)));
    }

    @Test
    void doesNotClearNativeMessagesOfProtectedToolsOrErrorResults() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(nativeToolMessage("call-write", "write_file", "wrote file\n" + "w".repeat(4_000)));
        messages.add(nativeToolMessage("call-tests", "shell", "BUILD FAILURE\nerror: compile\n" + "t".repeat(4_000)));

        assertFalse(pruner.hasPrunableHistoricalToolResult(messages, 99, 10_000));
        assertFalse(pruner.pruneAllEligible(messages).changed());
        assertEquals("wrote file\n" + "w".repeat(4_000), content(messages.get(1)));
        assertEquals("BUILD FAILURE\nerror: compile\n" + "t".repeat(4_000), content(messages.get(2)));
    }

    @Test
    void neverSelectsProtectedToolResultBecauseItCannotBeReconstructed() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(nativeToolMessage("call-skill", "skill", "SKILL manual\n" + "s".repeat(6_000)));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("skill", "another manual\n" + "k".repeat(6_000))));
        messages.add(message("assistant", ""));

        assertFalse(pruner.hasPrunableHistoricalToolResult(messages, 0, 0));
        assertTrue(pruner.selectPrunableIndexes(messages, 0, 0).isEmpty());
        assertFalse(pruner.pruneAllEligible(messages).changed());
        assertTrue(content(messages.get(1)).contains("SKILL manual"));
        assertTrue(content(messages.get(3)).contains("another manual"));
    }

    @Test
    void protectsTheMostRecentEligibleToolOutputsWithinTheProtectBudget() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("grep", "match\n" + "g".repeat(5_000))));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("read_file", "file body\n" + "r".repeat(5_000))));
        messages.add(message("assistant", ""));
        messages.add(message("user", "recent"));

        List<Integer> selected = new TurnAwareContextPruner(String::length, 6_000, 1_000)
                .selectPrunableIndexes(messages, 1, 500);

        // 最新的 read_file 输出落在 6k 保护区里，必须原样保留；更早的 grep 才允许占位化。
        assertEquals(List.of(2), selected);
        assertEquals(tool("read_file", "file body\n" + "r".repeat(5_000)), content(messages.get(4)));
    }

    @Test
    void clearingIsIdempotentForAlreadyClearedContent() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(nativeToolMessage("call-grep", "grep", "match\n" + "g".repeat(4_000)));
        messages.add(message("assistant", ""));

        TurnAwareContextPruner.Result first = pruner.pruneAllEligible(messages);
        assertTrue(first.changed());
        String cleared = content(messages.get(1));

        TurnAwareContextPruner.Result second = pruner.pruneAllEligible(messages);
        assertFalse(second.changed());
        assertEquals(0, second.prunedToolResults());
        assertEquals(cleared, content(messages.get(1)));
    }

    @Test
    void reportsNoEligibleHistoricalResultWhenOnlyProtectedOutputExists() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("apply_patch", "patch applied\n" + "x".repeat(5_000))));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("run_tests", "BUILD SUCCESS\n" + "y".repeat(5_000))));

        assertFalse(pruner.hasPrunableHistoricalToolResult(messages, 1, 2_000));
        assertTrue(pruner.selectPrunableIndexes(messages, 1, 2_000).isEmpty());
        assertFalse(pruner.pruneAllEligible(messages).changed());
    }

    private List<Map<String, Object>> messages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial context"));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("write_file", "wrote backend/src/main/java/App.java\n" + "w".repeat(3_000))));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("grep", "match\n" + "g".repeat(4_000))));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("run_tests", "BUILD SUCCESS\n" + "t".repeat(3_000))));
        messages.add(message("assistant", ""));
        messages.add(message("user", tool("read_file", "recent file contents\n" + "r".repeat(2_000))));
        return messages;
    }

    private String tool(String tool, String output) {
        return "[Tool " + tool + " result]\n" + output;
    }

    private Map<String, Object> nativeToolMessage(String toolCallId, String toolName, String content) {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("name", toolName);
        message.put("content", content);
        message.put("metadata", Map.of("turn", 1));
        return message;
    }

    private Map<String, Object> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private String content(Map<String, Object> message) {
        return (String) message.get("content");
    }
}
