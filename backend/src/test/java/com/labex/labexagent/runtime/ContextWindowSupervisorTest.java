package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.labex.entity.AgentModelConfig;
import org.junit.jupiter.api.Test;

class ContextWindowSupervisorTest {

    private final ContextWindowSupervisor supervisor = new ContextWindowSupervisor();

    @Test
    void selectsPruningBeforeCheckpointWhenEligibleOutputExists() {
        ContextWindowPolicy policy = ContextWindowPolicy.from(config()).orElseThrow();

        ContextWindowSupervisor.Decision decision = supervisor.decide(policy, policy.softLimitTokens() + 1, true);

        assertEquals(ContextWindowSupervisor.Action.PRUNE, decision.action());
    }

    @Test
    void selectsCheckpointWhenNoEligibleToolOutputExists() {
        ContextWindowPolicy policy = ContextWindowPolicy.from(config()).orElseThrow();

        ContextWindowSupervisor.Decision decision = supervisor.decide(policy, policy.softLimitTokens() + 1, false);

        assertEquals(ContextWindowSupervisor.Action.CHECKPOINT, decision.action());
    }

    @Test
    void leavesContextUntouchedUnderTheSoftLimit() {
        ContextWindowPolicy policy = ContextWindowPolicy.from(config()).orElseThrow();

        ContextWindowSupervisor.Decision decision = supervisor.decide(policy, policy.softLimitTokens(), true);

        assertEquals(ContextWindowSupervisor.Action.NONE, decision.action());
    }

    private AgentModelConfig config() {
        AgentModelConfig config = new AgentModelConfig();
        config.setContextWindowTokens(20_000);
        config.setMaxTokens(4_000);
        config.setCompactionAuto(1);
        config.setCompactionPrune(1);
        return config;
    }
}