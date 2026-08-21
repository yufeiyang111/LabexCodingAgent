package com.labex.monitor.health;

import java.util.Map;

/** 单个依赖健康状态的对外展示 DTO。 */
public record DependencyHealthStatus(
        String dependencyName,
        String status,
        String checkedAt,
        long latencyMs,
        String safeMessage,
        String errorCode,
        boolean affectsCoreService,
        Map<String, Object> details) {
}
