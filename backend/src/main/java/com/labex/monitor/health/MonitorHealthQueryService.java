package com.labex.monitor.health;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * 组合健康检查用例：按配置超时并行/顺序执行全部 HealthCheck，隔离每个依赖的失败与超时，
 * 单个依赖异常不会拖垮整个健康接口。所有面向用户信息已经过安全脱敏。
 */
@Service
public class MonitorHealthQueryService implements DisposableBean {

    private final List<HealthCheck> healthChecks;
    private final HealthSnapshotAssembler assembler;
    private final MonitorHealthProperties properties;
    private final ExecutorService executor;

    public MonitorHealthQueryService(List<HealthCheck> healthChecks, HealthSnapshotAssembler assembler,
                                     MonitorHealthProperties properties) {
        this.healthChecks = healthChecks;
        this.assembler = assembler;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getExecutorPoolSize()));
    }

    public HealthSnapshot summary() {
        long start = System.nanoTime();
        List<HealthCheckResult> results = new ArrayList<>(healthChecks.size());
        for (HealthCheck check : healthChecks) {
            results.add(runIsolated(check));
        }
        long totalLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return assembler.assemble(results, Instant.now(), totalLatencyMs);
    }

    public List<DependencyHealthStatus> dependencies() {
        return summary().dependencies();
    }

    private HealthCheckResult runIsolated(HealthCheck check) {
        Future<HealthCheckResult> future = executor.submit(check::check);
        try {
            HealthCheckResult result = future.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
            if (result == null) {
                return HealthCheckResult.down(check.name(), check.affectsCoreService(), 0L,
                        "依赖检查未返回结果", "EMPTY_RESULT", Map.of());
            }
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            return HealthCheckResult.timeout(check.name(), check.affectsCoreService(), properties.getTimeoutMs());
        } catch (Exception e) {
            return HealthCheckResult.down(check.name(), check.affectsCoreService(), 0L,
                    "依赖检查失败", "CHECK_FAILED", Map.of());
        }
    }

    @Override
    public void destroy() {
        shutdown();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
