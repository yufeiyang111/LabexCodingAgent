package com.labex.labexagent.run;

public final class BackgroundDeliveryRetryPolicy {
    public static final int MAX_ATTEMPTS = 3;
    public boolean mayRetry(int attemptsAlreadyMade, boolean retryableFailure) {
        return retryableFailure && attemptsAlreadyMade >= 0 && attemptsAlreadyMade < MAX_ATTEMPTS;
    }
}
