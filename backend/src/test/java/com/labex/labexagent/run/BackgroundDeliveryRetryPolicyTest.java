package com.labex.labexagent.run;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
class BackgroundDeliveryRetryPolicyTest {
 private final BackgroundDeliveryRetryPolicy policy = new BackgroundDeliveryRetryPolicy();
 @Test void allowsOnlyThreeRetryableAttempts() { assertTrue(policy.mayRetry(0,true)); assertTrue(policy.mayRetry(2,true)); assertFalse(policy.mayRetry(3,true)); assertFalse(policy.mayRetry(0,false)); }
}
