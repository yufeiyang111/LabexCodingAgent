package com.labex.monitor.health;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 将各依赖检查结果组装为对外 HealthSnapshot，并计算整体状态。 */
@Component
public class HealthSnapshotAssembler {

    /**
     * 整体状态规则：
     * 任一影响核心服务的依赖 DOWN → 整体 DOWN；
     * 存在任意 DOWN（非核心）或 DEGRADED → 整体 DEGRADED；
     * 否则（全部 UP/UNKNOWN/空列表）→ 整体 UP。
     * 名称为空或 null 的检查结果会被忽略。
     */
    public HealthSnapshot assemble(List<HealthCheckResult> results, Instant checkedAt, long totalLatencyMs) {
        List<DependencyHealthStatus> dependencies = new ArrayList<>();
        int up = 0;
        int degraded = 0;
        int down = 0;
        boolean coreDown = false;
        boolean anyDown = false;
        boolean anyDegraded = false;

        for (HealthCheckResult result : results) {
            if (result == null || result.name() == null || result.name().isBlank()) {
                continue;
            }
            dependencies.add(toStatus(result));
            switch (result.status()) {
                case DOWN -> {
                    down++;
                    anyDown = true;
                    if (result.affectsCoreService()) {
                        coreDown = true;
                    }
                }
                case DEGRADED -> {
                    degraded++;
                    anyDegraded = true;
                }
                default -> up++;
            }
        }

        HealthStatus overall;
        if (coreDown) {
            overall = HealthStatus.DOWN;
        } else if (anyDown || anyDegraded) {
            overall = HealthStatus.DEGRADED;
        } else {
            overall = HealthStatus.UP;
        }

        return new HealthSnapshot(overall.name(), checkedAt.toString(), totalLatencyMs,
                dependencies.size(), up, degraded, down, dependencies);
    }

    private static DependencyHealthStatus toStatus(HealthCheckResult result) {
        return new DependencyHealthStatus(
                result.name(),
                result.status().name(),
                result.checkedAt().toString(),
                result.latencyMs(),
                result.safeMessage(),
                result.errorCode(),
                result.affectsCoreService(),
                result.details());
    }
}
