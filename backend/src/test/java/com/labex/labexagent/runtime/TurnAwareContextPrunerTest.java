package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TurnAwareContextPrunerTest {

    private final TurnAwareContextPruner pruner = new TurnAwareContextPruner(String::length);

    @Test
    void clearsOnlyEligibleHistoricalToolResultsAndPreservesProtectedAndRecentTurns() {
        List<Map<String, Object>> messages = messages();
        String protectedWrite = content(messages.get(2));
        List<Map<String, Object>> retainedTail = List.copyOf(messages.subList(5, messages.size()));

        TurnAwareContextPruner.Result result = pruner.prune(messages, 2, 4_000);

        assertTrue(result.changed());
        assertEquals(1, result.prunedToolResults());
        assertTrue(content(messages.get(4)).contains("[Old tool result content cleared."));
        assertEquals(protectedWrite, content(messages.get(2)));
        assertEquals(retainedTail, messages.subList(5, messages.size()));
        assertTrue(result.tokensAfter() < result.tokensBefore());
    }

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
    void clearsNativeToolMessagesWithoutDisguisedPrefixAndPreservesProtocolFields() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(message("assistant", ""));
        messages.add(nativeToolMessage("call-grep", "grep", "match\n" + "g".repeat(4_000)));
        messages.add(message("assistant", ""));
        messages.add(message("user", "recent"));

        assertTrue(pruner.hasPrunableHistoricalToolResult(messages, 1, 2_000));

        TurnAwareContextPruner.Result result = pruner.prune(messages, 1, 500);

        assertTrue(result.changed());
        assertEquals(1, result.prunedToolResults());
        Map<String, Object> cleared = messages.get(2);
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
    void preservesProtocolMetadataWhenClearingHistoricalContent() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "initial"));
        messages.add(message("assistant", ""));
        Map<String, Object> historical = new java.util.LinkedHashMap<>();
        historical.put("role", "tool");
        historical.put("tool_call_id", "call-grep");
        historical.put("name", "grep");
        historical.put("content", tool("grep", "match\n" + "g".repeat(4_000)));
        historical.put("metadata", Map.of("turn", 1));
        messages.add(historical);
        messages.add(message("assistant", ""));
        messages.add(message("user", "recent"));

        pruner.prune(messages, 1, 500);

        assertEquals("call-grep", messages.get(2).get("tool_call_id"));
        assertEquals("grep", messages.get(2).get("name"));
        assertEquals(Map.of("turn", 1), messages.get(2).get("metadata"));
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
        assertFalse(pruner.prune(messages, 1, 2_000).changed());
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
