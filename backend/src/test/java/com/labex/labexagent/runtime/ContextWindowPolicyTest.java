package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.AgentModelConfig;
import org.junit.jupiter.api.Test;

class ContextWindowPolicyTest {

    @Test
    void derivesSafeDefaultsFromTheModelWindow() {
        AgentModelConfig config = config(100_000, 8_192);

        ContextWindowPolicy policy = ContextWindowPolicy.from(config).orElseThrow();

        assertTrue(policy.autoCompactionEnabled());
        assertFalse(policy.pruningEnabled());
        assertEquals(91_808, policy.inputCapacityTokens());
        assertEquals(8_192, policy.reservedTokens());
        assertEquals(82_627, policy.softLimitTokens());
        assertEquals(2, policy.tailTurns());
        assertEquals(8_000, policy.preserveRecentTokens());
    }

    @Test
    void honorsExplicitPolicyOverrides() {
        AgentModelConfig config = config(40_000, 8_000);
        config.setCompactionAuto(0);
        config.setCompactionPrune(1);
        config.setCompactionTailTurns(3);
        config.setCompactionReservedTokens(4_000);
        config.setCompactionPreserveRecentTokens(3_500);

        ContextWindowPolicy policy = ContextWindowPolicy.from(config).orElseThrow();

        assertFalse(policy.autoCompactionEnabled());
        assertTrue(policy.pruningEnabled());
        assertEquals(4_000, policy.reservedTokens());
        assertEquals(28_000, policy.softLimitTokens());
        assertEquals(3, policy.tailTurns());
        assertEquals(3_500, policy.preserveRecentTokens());
    }

    @Test
    void appliesExplicitThresholdBeforeTheReserveLimit() {
        AgentModelConfig config = config(40_000, 8_000);
        config.setCompactionReservedTokens(2_000);
        config.setCompactionThresholdPercent(80);

        ContextWindowPolicy policy = ContextWindowPolicy.from(config).orElseThrow();

        assertEquals(32_000, policy.inputCapacityTokens());
        assertEquals(2_000, policy.reservedTokens());
        assertEquals(25_600, policy.softLimitTokens());
    }

    @Test
    void disablesPolicyWithoutAValidInputWindow() {
        assertTrue(ContextWindowPolicy.from(config(8_000, 8_000)).isEmpty());
        assertTrue(ContextWindowPolicy.from(config(null, 8_000)).isEmpty());
    }

    private AgentModelConfig config(Integer contextWindowTokens, Integer maxTokens) {
        AgentModelConfig config = new AgentModelConfig();
        config.setContextWindowTokens(contextWindowTokens);
        config.setMaxTokens(maxTokens);
        return config;
    }
}