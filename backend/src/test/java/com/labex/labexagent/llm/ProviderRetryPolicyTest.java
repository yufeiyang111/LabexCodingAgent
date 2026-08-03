package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProviderRetryPolicyTest {
    private final ProviderRetryPolicy policy = new ProviderRetryPolicy();
    private final LlmProvider.LlmConfig config = new LlmProvider.LlmConfig(
            "test-key", "https://example.com", "test-model", 32, 0.1, 15_000, 900_000, 4);

    @Test
    void retriesProviderTimeoutAtMostOnce() {
        ProviderFailure timeout = new ProviderFailure(
                ProviderFailureType.TIMEOUT, null, "Read timed out", true);

        assertTrue(policy.shouldRetry(timeout, 0, config));
        assertFalse(policy.shouldRetry(timeout, 1, config));
    }

    @Test
    void classifiesJdkHttpRequestTimeoutAsATimeoutFailure() {
        ProviderFailure failure = policy.exceptionFailure(
                new java.net.http.HttpTimeoutException("request timed out"));

        assertEquals(ProviderFailureType.TIMEOUT, failure.type());
        assertTrue(failure.retryable());
    }

    @Test
    void formatsActualRateLimitDetailsWithoutExposingCredentials() {
        ProviderFailure failure = policy.httpFailure(429,
                "API error 429: {\"error\":{\"message\":\"rate limited; retry after 30 seconds\",\"api_key\":\"secret-value\"}}");

        assertTrue(failure.message().contains("HTTP 429 Too Many Requests"));
        assertTrue(failure.message().contains("rate limited; retry after 30 seconds"));
        assertFalse(failure.message().contains("secret-value"));
        assertTrue(failure.message().contains("[redacted]"));
    }

    @Test
    void labelsAuthenticationAndServerErrorsWithTheirHttpStatus() {
        assertTrue(policy.httpFailure(401, "API error 401: invalid key").message()
                .contains("HTTP 401 Unauthorized"));
        assertTrue(policy.httpFailure(503, "API error 503: provider overloaded").message()
                .contains("HTTP 503 Service Unavailable"));
    }

    @Test
    void keepsConfiguredRetryBudgetForOtherTransientNetworkFailures() {
        ProviderFailure network = new ProviderFailure(
                ProviderFailureType.NETWORK, null, "Connection reset", true);

        assertTrue(policy.shouldRetry(network, 1, config));
    }
}
