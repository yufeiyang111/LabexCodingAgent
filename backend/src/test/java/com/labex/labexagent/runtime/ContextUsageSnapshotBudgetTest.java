package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextUsageSnapshotBudgetTest {
    @Test
    void payloadSeparatesStaticAndReducibleTokensAndIncludesReservedOutput() {
        ContextBudgetBreakdown budget = new ContextBudgetBreakdown(
                16000, 12000, 4000,
                Map.of("systemPrompt", 1000, "toolDefinitions", 800),
                Map.of("conversationMessages", 2400), 11000);
        ContextUsageSnapshot snapshot = new ContextUsageSnapshot(
                "conversation", "session", "provider", "model", 16000,
                Map.of("systemPrompt", 1000, "toolDefinitions", 800, "conversationMessages", 2400), "NONE")
                .withBudgetBreakdown(budget);

        Map<String, Object> payload = snapshot.toPayload();

        assertEquals(1800, payload.get("staticTokens"));
        assertEquals(2400, payload.get("reducibleTokens"));
        assertEquals(4000, payload.get("reservedOutputTokens"));
        assertEquals(budget.staticCategories(), payload.get("staticCategories"));
        assertEquals(budget.reducibleCategories(), payload.get("reducibleCategories"));
        assertEquals(11_000, payload.get("softLimitTokens"));
        assertEquals("context-budget-v3", payload.get("contextCategoryVersion"));
    }
}
