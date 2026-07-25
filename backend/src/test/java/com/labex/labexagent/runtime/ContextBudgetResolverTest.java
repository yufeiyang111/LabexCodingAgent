package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.AgentModelConfig;
import org.junit.jupiter.api.Test;

class ContextBudgetResolverTest {

    @Test
    void resolvesConfiguredContextWindowMinusMaxOutputTokens() {
        AgentModelConfig config = new AgentModelConfig();
        config.setContextWindowTokens(1_000_000);
        config.setMaxTokens(32_768);

        assertEquals(967_232, ContextBudgetResolver.resolveInputBudget(config).orElseThrow());
    }

    @Test
    void usesConfiguredContextWindowWhenOutputAllocationIsMissing() {
        AgentModelConfig config = new AgentModelConfig();
        config.setContextWindowTokens(8_192);

        assertEquals(8_192, ContextBudgetResolver.resolveInputBudget(config).orElseThrow());
    }

    @Test
    void returnsEmptyWhenContextWindowTokensAreNotConfigured() {
        AgentModelConfig config = new AgentModelConfig();
        config.setMaxTokens(32_768);

        assertTrue(ContextBudgetResolver.resolveInputBudget(config).isEmpty());
    }

    @Test
    void returnsEmptyWhenConfiguredInputBudgetWouldNotBePositive() {
        AgentModelConfig config = new AgentModelConfig();
        config.setContextWindowTokens(4_096);
        config.setMaxTokens(4_096);

        assertTrue(ContextBudgetResolver.resolveInputBudget(config).isEmpty());

        config.setMaxTokens(4_097);
        assertTrue(ContextBudgetResolver.resolveInputBudget(config).isEmpty());
    }

    @Test
    void returnsEmptyForNonPositiveContextWindow() {
        AgentModelConfig config = new AgentModelConfig();
        config.setContextWindowTokens(0);
        config.setMaxTokens(1);

        assertTrue(ContextBudgetResolver.resolveInputBudget(config).isEmpty());
    }
}
