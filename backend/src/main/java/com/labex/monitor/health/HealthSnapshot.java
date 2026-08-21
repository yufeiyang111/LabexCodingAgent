package com.labex.monitor.health;

import java.util.List;

/** 服务整体健康快照：整体状态 + 每个依赖的状态。 */
public record HealthSnapshot(
        String overallStatus,
        String checkedAt,
        long totalLatencyMs,
        int dependencyCount,
        int upCount,
        int degradedCount,
        int downCount,
        List<DependencyHealthStatus> dependencies) {
}
