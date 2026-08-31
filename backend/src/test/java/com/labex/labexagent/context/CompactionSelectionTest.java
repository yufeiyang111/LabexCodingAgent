package com.labex.labexagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.runtime.AgentProviderMessageProjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompactionSelectionTest {

    private final AgentRequestTokenEstimator estimator = new AgentRequestTokenEstimator();

    @Test
    void retainsCompleteRecentUserTurnsIncludingEntireNativeToolBatch() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "initial context"));
        messages.add(Map.of("role", "assistant", "content", "old answer"));
        messages.add(Map.of("role", "user", "content", "inspect project"));
        messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                toolCall("call-1", "read_file"), toolCall("call-2", "grep"))));
        messages.add(Map.of("role", "tool", "tool_call_id", "call-1", "name", "read_file", "content", "file body"));
        messages.add(Map.of("role", "tool", "tool_call_id", "call-2", "name", "grep", "content", "matches"));
        messages.add(Map.of("role", "assistant", "content", "inspection complete"));
        messages.add(Map.of("role", "user", "content", "now fix it"));
        messages.add(Map.of("role", "assistant", "content", "working"));

        CompactionSelection selection = CompactionSelection.select(messages, 2, 10_000, estimator);

        assertTrue(selection.changed());
        assertEquals(2, selection.retainedTurns());
        assertEquals("inspect project", selection.retainedTail().get(0).get("content"));
        assertEquals("working", selection.retainedTail().get(selection.retainedTail().size() - 1).get("content"));
        assertEquals(7, selection.retainedTail().size());
        new AgentProviderMessageProjector().project(selection.retainedTail());
    }

    @Test
    void doesNotCountTheObjectiveAnchorAsASeparateUserTurn() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "initial context"),
                Map.of("role", "assistant", "content", "old answer"),
                Map.of("role", "user", "content", "<agent_focus_anchor version=\"1\">\nobjective\n</agent_focus_anchor>"),
                Map.of("role", "user", "content", "actual latest request"),
                Map.of("role", "assistant", "content", "working"));

        CompactionSelection selection = CompactionSelection.select(messages, 1, 10_000, estimator);

        assertTrue(selection.changed());
        assertEquals(1, selection.retainedTurns());
        assertEquals("actual latest request", selection.retainedTail().get(0).get("content"));
    }

    @Test
    void neverSplitsLatestTurnEvenWhenItExceedsTailBudget() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "old"),
                Map.of("role", "assistant", "content", "old answer"),
                Map.of("role", "user", "content", "latest"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall("call-1", "run_tests"))),
                Map.of("role", "tool", "tool_call_id", "call-1", "name", "run_tests", "content", "x".repeat(4_000)));

        CompactionSelection selection = CompactionSelection.select(messages, 2, 10, estimator);

        assertTrue(selection.changed());
        assertEquals(1, selection.retainedTurns());
        assertEquals(3, selection.retainedTail().size());
        assertFalse(selection.retainedTail().stream().anyMatch(message -> "old".equals(message.get("content"))));
        new AgentProviderMessageProjector().project(selection.retainedTail());
    }

    @Test
    void splitsLongSingleUserTurnWithMultipleToolStepsWhenExceedingBudget() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "start long task"));
        for (int i = 1; i <= 8; i++) {
            messages.add(Map.of("role", "assistant", "content", "step " + i,
                    "tool_calls", List.of(toolCall("call-" + i, "read_file"))));
            messages.add(Map.of("role", "tool", "tool_call_id", "call-" + i, "name", "read_file",
                    "content", "data for step " + i + " " + "x".repeat(500)));
        }

        // Total messages = 17. Budget fits roughly the last 2 steps.
        CompactionSelection selection = CompactionSelection.select(messages, 2, 2_000, estimator);

        assertTrue(selection.changed());
        assertEquals(1, selection.retainedTurns());
        // Head should contain earlier steps, tail should contain recent steps
        assertTrue(selection.compactedHead().size() > 0);
        assertTrue(selection.retainedTail().size() > 0);
        assertEquals(messages.size(), selection.compactedHead().size() + selection.retainedTail().size());
        // Verify tail starts with a valid role (not an orphaned 'tool')
        String tailFirstRole = String.valueOf(selection.retainedTail().get(0).get("role"));
        assertTrue("assistant".equals(tailFirstRole) || "user".equals(tailFirstRole));
        new AgentProviderMessageProjector().project(selection.retainedTail());
    }

    private Map<String, Object> toolCall(String id, String name) {
        return Map.of("id", id, "type", "function",
                "function", Map.of("name", name, "arguments", "{}"));
    }
}