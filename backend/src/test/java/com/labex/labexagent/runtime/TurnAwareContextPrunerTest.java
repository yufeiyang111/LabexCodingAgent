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

    private Map<String, Object> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private String content(Map<String, Object> message) {
        return (String) message.get("content");
    }
}