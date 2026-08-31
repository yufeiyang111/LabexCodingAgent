package com.labex.labexagent.llm;

import java.util.Map;

/**
 * Provider 传输层失败。diagnostics 携带失败点的结构化现场（endpoint、超时配置、耗时、
 * 失败阶段、重试次数等），供 run log 与运维定位使用；不含任何密钥或请求体内容。
 */
public record ProviderFailure(ProviderFailureType type, Integer statusCode, String message, boolean retryable,
                              Map<String, Object> diagnostics) {
    public ProviderFailure(ProviderFailureType type, Integer statusCode, String message, boolean retryable) {
        this(type, statusCode, message, retryable, Map.of());
    }

    public ProviderFailure {
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public ProviderFailure withDiagnostics(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return this;
        return new ProviderFailure(type, statusCode, message, retryable, value);
    }
}
