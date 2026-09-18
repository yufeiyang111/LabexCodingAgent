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
        ContextAdmissionDecision decision = service.decideAfterContextManagement(breakdown(300, 200, 1000, 900), true);
        assertEquals(ContextAdmissionDecision.Action.PROCEED, decision.action());
        assertTrue(decision.providerInvocationAllowed());
    }

    @Test
    void blocksReducibleOverflowWithAutoCompactionReasonWhenManagementFailed() {
        // 上下文管理（占位化 + compaction）已运行，仍未降到输入容量内 → 必须阻断，且原因指向压缩。
        ContextAdmissionDecision decision = service.decideAfterContextManagement(
                new ContextBudgetBreakdown(1000, 800, 200,
                        Map.of("systemPrompt", 400), Map.of("conversationMessages", 500), 700), true);

        assertEquals(ContextAdmissionDecision.Action.BLOCK_REDUCIBLE_OVERFLOW, decision.action());
        assertFalse(decision.providerInvocationAllowed());
        assertEquals("reducible_context_exceeds_input_capacity_after_auto_compaction", decision.reasonCode());
    }

    @Test
    void blocksReducibleOverflowWithConfigReasonWhenAutoCompactionIsDisabled() {
        // 同一份超限预算，自动压缩关闭时不能把"压缩没生效"当成原因——那是配置禁用。
        ContextAdmissionDecision decision = service.decideAfterContextManagement(
                new ContextBudgetBreakdown(1000, 800, 200,
                        Map.of("systemPrompt", 400), Map.of("conversationMessages", 500), 700), false);

        assertEquals(ContextAdmissionDecision.Action.BLOCK_REDUCIBLE_OVERFLOW, decision.action());
        assertFalse(decision.providerInvocationAllowed());
        assertEquals("reducible_context_exceeds_input_capacity", decision.reasonCode());
    }

    @Test
    void blocksStaticOverflowWithCategoryEvidenceAndRemediation() {
        ContextBudgetBreakdown breakdown = new ContextBudgetBreakdown(
                1000, 900, 100,
                Map.of("systemPrompt", 500, "toolDefinitions", 450),
                Map.of("conversationMessages", 20));
        ContextAdmissionDecision decision = service.decideAfterContextManagement(breakdown, true);

        assertEquals(ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW, decision.action());
        assertFalse(decision.providerInvocationAllowed());
        assertEquals(950, decision.breakdown().staticTokens());
        assertTrue(decision.remediation().stream().anyMatch(item -> item.contains("工具")));
    }

    @Test
    void blocksWhenStaticContextAloneExceedsSoftLimitWithoutReducibleHistory() {
        // 静态上下文本身没超输入容量，但已越过软限且没有任何可约减历史 → 无从下手，结构化阻断。
        ContextBudgetBreakdown breakdown = new ContextBudgetBreakdown(
                1000, 900, 100, Map.of("systemPrompt", 850), Map.of(), 700);
        ContextAdmissionDecision decision = service.decideAfterContextManagement(breakdown, true);

        assertEquals(ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW, decision.action());
        assertEquals("static_context_exceeds_soft_limit", decision.reasonCode());
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
