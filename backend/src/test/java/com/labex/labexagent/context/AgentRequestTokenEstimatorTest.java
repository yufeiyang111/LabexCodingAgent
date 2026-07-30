package com.labex.labexagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRequestTokenEstimatorTest {

    private final AgentRequestTokenEstimator estimator = new AgentRequestTokenEstimator();

    @Test
    void countsSystemToolsNativeToolProtocolAndOutputReserve() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "name", "operator", "content", "run verification"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "type", "function",
                        "function", Map.of("name", "run_tests", "arguments", "{\"command\":\"mvn test\"}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "name", "run_tests", "content", "BUILD SUCCESS"));
        List<Map<String, Object>> tools = List.of(Map.of(
                "type", "function",
                "function", Map.of("name", "run_tests", "description", "Run the repository test suite")));

        AgentRequestTokenEstimator.Estimate estimate = estimator.estimate(
                "system instructions", tools, messages, 4_096, 512);

        assertTrue(estimate.systemPromptTokens() > 0);
        assertTrue(estimate.toolSchemaTokens() > 0);
        assertTrue(estimate.messageTokens() > estimator.estimateMessages(List.of(
                Map.of("role", "user", "content", "run verification"))));
        assertEquals(estimate.inputTokens() + 512, estimate.totalWithReservedOutputTokens());
        assertEquals(3_584, estimate.inputCapacityTokens());
        assertFalse(estimate.overflowsInputCapacity());
    }

    @Test
    void rejectsUnknownOrUnsafeModelWindowInsteadOfUsingPermissiveDefault() {
        AgentContextOverflowException missing = assertThrows(AgentContextOverflowException.class,
                () -> estimator.estimate("system", List.of(), List.of(), null, 512));
        assertEquals("context_window_unconfigured", missing.reasonCode());

        AgentContextOverflowException invalidReserve = assertThrows(AgentContextOverflowException.class,
                () -> estimator.estimate("system", List.of(), List.of(), 1_024, 1_024));
        assertEquals("output_reserve_exhausts_context_window", invalidReserve.reasonCode());
    }
}