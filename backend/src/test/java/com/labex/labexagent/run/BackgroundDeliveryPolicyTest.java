package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BackgroundDeliveryPolicyTest {
    private final BackgroundDeliveryPolicy policy = new BackgroundDeliveryPolicy();

    @Test void deniesEveryDeliveryActionByDefault() {
        var result = policy.evaluate(new BackgroundDeliveryPolicy.Request(BackgroundDeliveryPolicy.Action.PUSH, true, false, true));
        assertFalse(result.allowed());
    }

    @Test void protectsHumanManagedBranchesEvenWhenApprovalAndVerificationExist() {
        var result = policy.evaluate(new BackgroundDeliveryPolicy.Request(BackgroundDeliveryPolicy.Action.PULL_REQUEST, false, true, true));
        assertFalse(result.allowed());
    }

    @Test void permitsOnlyExplicitlyApprovedVerifiedDeliveryOnAgentBranch() {
        var result = policy.evaluate(new BackgroundDeliveryPolicy.Request(BackgroundDeliveryPolicy.Action.COMMIT, true, true, true));
        assertTrue(result.allowed());
    }
}
