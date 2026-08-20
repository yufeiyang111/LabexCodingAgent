package com.labex.auth;

import java.util.Collections;
import java.util.Map;

/** 认证业务异常，Controller 只将稳定信息映射到 Result。 */
public class AuthException extends RuntimeException {
    private final int code;
    private final Map<String, Object> details;
    private final long retryAfterSeconds;

    public AuthException(int code, String message) {
        this(code, message, Collections.emptyMap(), 0);
    }

    public AuthException(int code, String message, Map<String, Object> details) {
        this(code, message, details, 0);
    }

    public AuthException(int code, String message, Map<String, Object> details, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.details = details == null ? Collections.emptyMap() : Map.copyOf(details);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public int getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
