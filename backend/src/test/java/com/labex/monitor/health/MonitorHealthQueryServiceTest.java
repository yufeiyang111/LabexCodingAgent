package com.labex.monitor.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MonitorHealthQueryServiceTest {

    private final List<MonitorHealthQueryService> created = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        created.forEach(MonitorHealthQueryService::shutdown);
    }

    private MonitorHealthQueryService service(List<HealthCheck> checks, long timeoutMs) {
        MonitorHealthProperties properties = new MonitorHealthProperties();
        properties.setTimeoutMs(timeoutMs);
        properties.setExecutorPoolSize(4);
        MonitorHealthQueryService svc = new MonitorHealthQueryService(checks, new HealthSnapshotAssembler(), properties);
        created.add(svc);
        return svc;
    }

    private HealthCheck check(String name, boolean core, HealthStatus status) {
        return new HealthCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean affectsCoreService() {
                return core;
            }

            @Override
            public HealthCheckResult check() {
                return new HealthCheckResult(name, status, java.time.Instant.now(), 1L, "ok", null, core, Map.of());
            }
        };
    }

    @Test
    void healthyChecksProduceUpSnapshot() {
        MonitorHealthQueryService svc = service(List.of(
                check("mysql", true, HealthStatus.UP),
                check("redis", true, HealthStatus.UP)), 2000);

        HealthSnapshot snapshot = svc.summary();

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.UP.name());
        assertThat(snapshot.dependencies()).hasSize(2);
    }

    @Test
    void singleDependencyDownDoesNotBreakOthers() {
        MonitorHealthQueryService svc = service(List.of(
                check("mysql", true, HealthStatus.DOWN),
                check("redis", true, HealthStatus.UP)), 2000);

        HealthSnapshot snapshot = svc.summary();

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.DOWN.name());
        assertThat(snapshot.dependencies()).hasSize(2);
        assertThat(snapshot.dependencies().stream()
                .filter(d -> "redis".equals(d.dependencyName()))
                .allMatch(d -> d.status().equals(HealthStatus.UP.name()))).isTrue();
    }

    @Test
    void throwingCheckIsIsolatedAsDown() {
        HealthCheck boom = new HealthCheck() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public boolean affectsCoreService() {
                return true;
            }

            @Override
            public HealthCheckResult check() {
                throw new IllegalStateException("jdbc:mysql://user:supersecret@internal/db");
            }
        };
        MonitorHealthQueryService svc = service(List.of(boom, check("redis", true, HealthStatus.UP)), 2000);

        HealthSnapshot snapshot = svc.summary();

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.DOWN.name());
        var boomStatus = snapshot.dependencies().stream()
                .filter(d -> "boom".equals(d.dependencyName())).findFirst().orElseThrow();
        assertThat(boomStatus.status()).isEqualTo(HealthStatus.DOWN.name());
        assertThat(boomStatus.safeMessage()).doesNotContain("supersecret");
        assertThat(boomStatus.safeMessage()).doesNotContain("jdbc:mysql");
        assertThat(boomStatus.safeMessage()).doesNotContain("jdbc:mysql://");
        assertThat(snapshot.dependencies().stream()
                .filter(d -> "redis".equals(d.dependencyName()))
                .allMatch(d -> d.status().equals(HealthStatus.UP.name()))).isTrue();
    }

    @Test
    void slowCheckIsCutOffByTimeout() {
        HealthCheck slow = new HealthCheck() {
            @Override
            public String name() {
                return "slow-dep";
            }

            @Override
            public boolean affectsCoreService() {
                return true;
            }

            @Override
            public HealthCheckResult check() {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return new HealthCheckResult("slow-dep", HealthStatus.UP, java.time.Instant.now(), 1L, "ok", null, true, Map.of());
            }
        };
        MonitorHealthQueryService svc = service(List.of(slow, check("redis", true, HealthStatus.UP)), 100);

        HealthSnapshot snapshot = svc.summary();

        var slowStatus = snapshot.dependencies().stream()
                .filter(d -> "slow-dep".equals(d.dependencyName())).findFirst().orElseThrow();
        assertThat(slowStatus.status()).isEqualTo(HealthStatus.DOWN.name());
        assertThat(slowStatus.errorCode()).isEqualTo("TIMEOUT");
        assertThat(snapshot.dependencies().stream()
                .filter(d -> "redis".equals(d.dependencyName()))
                .allMatch(d -> d.status().equals(HealthStatus.UP.name()))).isTrue();
    }

    @Test
    void multipleFailedChecksAreAllIsolated() {
        MonitorHealthQueryService svc = service(List.of(
                check("mysql", true, HealthStatus.DOWN),
                check("redis", true, HealthStatus.DOWN),
                check("mcp", false, HealthStatus.DOWN),
                check("workspace", true, HealthStatus.UP)), 2000);

        HealthSnapshot snapshot = svc.summary();

        assertThat(snapshot.downCount()).isEqualTo(3);
        assertThat(snapshot.dependencies()).hasSize(4);
    }

    @Test
    void emptyCheckListIsUp() {
        MonitorHealthQueryService svc = service(List.of(), 2000);

        HealthSnapshot snapshot = svc.summary();

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.UP.name());
        assertThat(snapshot.dependencies()).isEmpty();
    }

    @Test
    void dependenciesEndpointReturnsFlatList() {
        MonitorHealthQueryService svc = service(List.of(
                check("mysql", true, HealthStatus.UP),
                check("redis", true, HealthStatus.UP)), 2000);

        List<DependencyHealthStatus> dependencies = svc.dependencies();

        assertThat(dependencies).hasSize(2);
        assertThat(dependencies.get(0).dependencyName()).isNotBlank();
        assertThat(dependencies.get(0).checkedAt()).isNotBlank();
    }

    @Test
    void shutdownIsIdempotent() {
        MonitorHealthQueryService svc = service(List.of(check("mysql", true, HealthStatus.UP)), 2000);
        svc.shutdown();
        svc.shutdown();
    }
}
