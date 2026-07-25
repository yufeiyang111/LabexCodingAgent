package com.labex.labexagent.llm;

public enum ProviderFailureType {
    AUTHENTICATION,
    RATE_LIMITED,
    TRANSIENT_SERVER,
    CLIENT_REQUEST,
    TIMEOUT,
    NETWORK,
    CANCELLED,
    UNKNOWN;

    public static ProviderFailureType fromHttpStatus(int statusCode) {
        if (statusCode == 401 || statusCode == 403) return AUTHENTICATION;
        if (statusCode == 408 || statusCode == 429) return RATE_LIMITED;
        if (statusCode >= 500 && statusCode <= 599) return TRANSIENT_SERVER;
        if (statusCode >= 400 && statusCode <= 499) return CLIENT_REQUEST;
        return UNKNOWN;
    }
}
