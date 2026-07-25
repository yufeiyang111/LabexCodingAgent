package com.labex.labexagent.llm;

public record ProviderFailure(ProviderFailureType type, Integer statusCode, String message, boolean retryable) {
}
