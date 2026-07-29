package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextAdmissionServiceTest {
    private final ContextAdmissionService service = new ContextAdmissionService();

    @Test
    void proceedsWhenInputFitsSoftLimit() {
        ContextAdmissionDecision decision = service.decide(breakdown(300, 200, 1000, 900));
        assertEquals(ContextAdmissionDecision.Action.PROCEED, decision.action());
        assertTrue(decision.providerInvocationAllowed());
    }

    @Test
    void prunesReducibleHistoryBeforeCompaction() {
        ContextBudgetBreakdown breakdown = breakdown(500, 450, 1000, 800);
        ContextAdmissionDecision decision = service.decide(breakdown, true, true);
        assertEquals(ContextAdmissionDecision.Action.PRUNE, decision.action());
        assertFalse(decision.providerInvocationAllowed());
    }

    @Test
    void compactsWhenPruningCannotAddressTheReducibleBudget() {
        ContextBudgetBreakdown breakdown = new ContextBudgetBreakdown(
                1000, 900, 100,
                Map.of("systemPrompt", 400, "toolDefinitions", 250),
                Map.of("projectContext", 250), 800);
        ContextAdmissionDecision decision = service.decide(breakdown, false, true);
        assertEquals(ContextAdmissionDecision.Action.COMPACT, decision.action());
        assertFalse(decision.providerInvocationAllowed());
    }

    @Test
    void blocksStaticOverflowWithCategoryEvidenceAndRemediation() {
        ContextBudgetBreakdown breakdown = new ContextBudgetBreakdown(
                1000, 900, 100,
                Map.of("systemPrompt", 500, "toolDefinitions", 450),
                Map.of("conversationMessages", 20));
        ContextAdmissionDecision decision = service.decide(breakdown, true, true);

        assertEquals(ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW, decision.action());
        assertFalse(decision.providerInvocationAllowed());
        assertEquals(950, decision.breakdown().staticTokens());
        assertTrue(decision.remediation().stream().anyMatch(item -> item.contains("工具")));
    }

    @Test
    void classifiesEstimatorCategoriesWithoutTreatingStaticPromptAsCompactable() {
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("systemPrompt", 300);
        categories.put("toolDefinitions", 200);
        categories.put("fixedInstructions", 100);
        categories.put("skillsAndInstructions", 50);
        categories.put("projectContext", 120);
        categories.put("conversationMessages", 80);
        categories.put("toolResults", 40);

        ContextBudgetBreakdown breakdown = service.breakdown(categories, 1200, 200, 900);

        assertEquals(650, breakdown.staticTokens());
        assertEquals(240, breakdown.reducibleTokens());
        assertEquals(890, breakdown.totalInputTokens());
        assertEquals(1090, breakdown.totalWithReservedOutputTokens());
    }

    private ContextBudgetBreakdown breakdown(int staticTokens, int reducibleTokens,
                                              int contextWindow, int softLimit) {
        return new ContextBudgetBreakdown(contextWindow, contextWindow - 100, 100,
                Map.of("systemPrompt", staticTokens),
                Map.of("conversationMessages", reducibleTokens), softLimit);
    }
}
