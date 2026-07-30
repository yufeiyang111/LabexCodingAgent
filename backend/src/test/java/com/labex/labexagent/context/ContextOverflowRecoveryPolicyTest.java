package com.labex.labexagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContextOverflowRecoveryPolicyTest {

    @Test
    void switchesStrategyOnceAndThenStopsInsteadOfRetryingForever() {
        ContextOverflowRecoveryPolicy policy = new ContextOverflowRecoveryPolicy();

        assertEquals(ContextOverflowRecoveryPolicy.Action.COMPACT, policy.next(true));
        assertEquals(ContextOverflowRecoveryPolicy.Action.REDUCE_TOOL_SCHEMA, policy.next(true));
        assertEquals(ContextOverflowRecoveryPolicy.Action.STOP, policy.next(true));
        assertEquals(ContextOverflowRecoveryPolicy.Action.STOP, policy.next(true));
        assertEquals(2, policy.attempts());
    }

    @Test
    void skipsUnavailableToolReductionAndStopsAfterCompaction() {
        ContextOverflowRecoveryPolicy policy = new ContextOverflowRecoveryPolicy();

        assertEquals(ContextOverflowRecoveryPolicy.Action.COMPACT, policy.next(false));
        assertEquals(ContextOverflowRecoveryPolicy.Action.STOP, policy.next(false));
    }
}