package com.labex.monitor.health;

import java.time.Instant;
import java.util.Map;

/**
 * 单个依赖检查的原始结果（HealthCheck 返回、聚合层使用）。
 * 所有面向用户的信息必须经过脱敏，禁止携带凭证、连接串、堆栈或完整敏感路径。
 */
public record HealthCheckResult(
        String name,
        HealthStatus status,
        Instant checkedAt,
        long latencyMs,
        String safeMessage,
        String errorCode,
        boolean affectsCoreService,
        Map<String, Object> details) {

    public static HealthCheckResult up(String name, boolean core, long latencyMs, String safeMessage,
                                       Map<String, Object> details) {
        return new HealthCheckResult(name, HealthStatus.UP, Instant.now(), latencyMs, safeMessage, null, core, details);
    }

    public static HealthCheckResult degraded(String name, boolean core, long latencyMs, String safeMessage,
                                             String errorCode, Map<String, Object> details) {
        return new HealthCheckResult(name, HealthStatus.DEGRADED, Instant.now(), latencyMs, safeMessage,
                errorCode, core, details);
    }

    public static HealthCheckResult down(String name, boolean core, long latencyMs, String safeMessage,
                                         String errorCode, Map<String, Object> details) {
        return new HealthCheckResult(name, HealthStatus.DOWN, Instant.now(), latencyMs, safeMessage, errorCode, core, details);
    }

    public static HealthCheckResult timeout(String name, boolean core, long timeoutMs) {
        return new HealthCheckResult(name, HealthStatus.DOWN, Instant.now(), timeoutMs, "依赖检查超时", "TIMEOUT", core, Map.of());
    }
}
