package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.labex.labexagent.context.AgentRequestTokenEstimator;
import org.junit.jupiter.api.Test;

class TokenAccountingConvergenceTest {
    private final AgentRequestTokenEstimator single = new AgentRequestTokenEstimator();
    private final ContextUsageEstimator contextUsage = new ContextUsageEstimator();

    @Test
    void contextUsageEstimatorDelegatesToSingleEstimatorForAscii() {
        assertEquals(single.estimateValue("ascii text"), contextUsage.estimateTokens("ascii text"));
    }

    @Test
    void contextUsageEstimatorDelegatesToSingleEstimatorForCjk() {
        assertEquals(single.estimateValue("中文内容测试中文内容"), contextUsage.estimateTokens("中文内容测试中文内容"));
    }

    @Test
    void contextUsageEstimatorDelegatesToSingleEstimatorForBlankAndNull() {
        assertEquals(0, contextUsage.estimateTokens(""));
        assertEquals(0, contextUsage.estimateTokens("   "));
        assertEquals(0, contextUsage.estimateTokens(null));
    }

    @Test
    void messageEstimationCoversFullJsonPlusEnvelopePerSingleEstimator() {
        java.util.Map<String, Object> message = java.util.Map.of("role", "user", "content", "中文内容");
        int tokens = single.estimateMessages(java.util.List.of(message));
        assertEquals(single.estimateValue(message) + 4, tokens);
    }
}
