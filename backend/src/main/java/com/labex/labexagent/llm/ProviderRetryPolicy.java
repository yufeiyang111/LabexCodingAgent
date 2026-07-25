package com.labex.labexagent.llm;

import com.labex.labexagent.runtime.CancellationToken;
import java.net.SocketTimeoutException;

/** Bounded retry policy for request failures that occur before a provider response is consumed. */
public class ProviderRetryPolicy {
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final long BASE_BACKOFF_MILLIS = 100L;
    private static final int MAX_PROVIDER_DETAIL_CHARS = 600;

    public int maxRetries(LlmProvider.LlmConfig config) {
        if (config != null && config.maxRetries() != null) {
            return Math.max(0, Math.min(config.maxRetries(), 4));
        }
        return DEFAULT_MAX_RETRIES;
    }

    public ProviderFailure httpFailure(int statusCode, String message) {
        ProviderFailureType type = ProviderFailureType.fromHttpStatus(statusCode);
        boolean retryable = type == ProviderFailureType.RATE_LIMITED || type == ProviderFailureType.TRANSIENT_SERVER;
        return new ProviderFailure(type, statusCode, formatHttpFailure(statusCode, type, message), retryable);
    }

    private String formatHttpFailure(int statusCode, ProviderFailureType type, String message) {
        String label = switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Provider Error";
        };
        String category = switch (type) {
            case AUTHENTICATION -> "authentication failed";
            case RATE_LIMITED -> "rate limited";
            case TRANSIENT_SERVER -> "provider temporarily unavailable";
            case CLIENT_REQUEST -> "request rejected";
            default -> "provider request failed";
        };
        String detail = sanitizeProviderDetail(message, statusCode);
        String summary = "HTTP " + statusCode + " " + label + " (" + category + ")";
        return detail.isBlank() ? summary : summary + ": " + detail;
    }

    private String sanitizeProviderDetail(String message, int statusCode) {
        String detail = message == null ? "" : message.trim();
        String prefix = "API error " + statusCode + ":";
        if (detail.regionMatches(true, 0, prefix, 0, prefix.length())) {
            detail = detail.substring(prefix.length()).trim();
        }
        detail = detail.replaceAll("(?i)(bearer\\s+)[^\\s,;\"}]+", "$1[redacted]");
        detail = detail.replaceAll("(?i)(\"?(?:api[_-]?key|authorization|token|password|secret)\"?\\s*[:=]\\s*\"?)([^\",\\s}]+)", "$1[redacted]");
        detail = detail.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return detail.length() <= MAX_PROVIDER_DETAIL_CHARS
                ? detail
                : detail.substring(0, MAX_PROVIDER_DETAIL_CHARS) + "...";
    }

    public ProviderFailure exceptionFailure(Exception exception) {
        if (exception instanceof SocketTimeoutException) {
            return new ProviderFailure(ProviderFailureType.TIMEOUT, null, exception.getMessage(), true);
        }
        return new ProviderFailure(ProviderFailureType.NETWORK, null,
                exception == null ? "Provider network failure" : String.valueOf(exception.getMessage()), true);
    }

    public boolean shouldRetry(ProviderFailure failure, int completedRetries, LlmProvider.LlmConfig config) {
        if (failure == null || !failure.retryable()) {
            return false;
        }
        int retryLimit = failure.type() == ProviderFailureType.TIMEOUT
                ? Math.min(1, maxRetries(config))
                : maxRetries(config);
        return completedRetries < retryLimit;
    }

    public boolean backoff(CancellationToken token, int completedRetries) {
        long delay = Math.min(1_000L, BASE_BACKOFF_MILLIS * (1L << Math.min(completedRetries, 3)));
        long deadline = System.nanoTime() + delay * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (token != null && token.isCancellationRequested()) {
                return false;
            }
            try {
                Thread.sleep(Math.min(25L, Math.max(1L, delay)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return token == null || !token.isCancellationRequested();
    }
}
