package com.labex.monitor.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthSnapshotAssemblerTest {

    private final HealthSnapshotAssembler assembler = new HealthSnapshotAssembler();

    private HealthCheckResult result(String name, HealthStatus status, boolean affectsCore, String safeMessage) {
        return new HealthCheckResult(name, status, Instant.now(), 5L, safeMessage, null, affectsCore, Map.of());
    }

    @Test
    void emptyDependencyListIsUpWithNoDependencies() {
        HealthSnapshot snapshot = assembler.assemble(List.of(), Instant.now(), 0L);

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.UP.name());
        assertThat(snapshot.dependencyCount()).isZero();
        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.upCount()).isZero();
        assertThat(snapshot.downCount()).isZero();
    }

    @Test
    void allUpIsUp() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                result("mysql", HealthStatus.UP, true, "ok"),
                result("redis", HealthStatus.UP, true, "ok")), Instant.now(), 10L);

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.UP.name());
        assertThat(snapshot.upCount()).isEqualTo(2);
        assertThat(snapshot.downCount()).isZero();
    }

    @Test
    void coreDependencyDownIsDown() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                result("mysql", HealthStatus.DOWN, true, "unavailable"),
                result("redis", HealthStatus.UP, true, "ok")), Instant.now(), 10L);

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.DOWN.name());
        assertThat(snapshot.downCount()).isEqualTo(1);
    }

    @Test
    void nonCoreDependencyDownIsDegraded() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                result("mysql", HealthStatus.UP, true, "ok"),
                result("mcp", HealthStatus.DOWN, false, "unavailable")), Instant.now(), 10L);

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.DEGRADED.name());
        assertThat(snapshot.downCount()).isEqualTo(1);
    }

    @Test
    void degradedDependencyIsDegraded() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                result("provider", HealthStatus.DEGRADED, true, "not configured")), Instant.now(), 10L);

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.DEGRADED.name());
        assertThat(snapshot.degradedCount()).isEqualTo(1);
    }

    @Test
    void mixedCoreDownAndNonCoreDownIsDown() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                result("mysql", HealthStatus.DOWN, true, "unavailable"),
                result("mcp", HealthStatus.DOWN, false, "unavailable")), Instant.now(), 10L);

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.DOWN.name());
        assertThat(snapshot.downCount()).isEqualTo(2);
    }

    @Test
    void unknownStatusIsTolerated() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                result("unknown-dep", HealthStatus.UNKNOWN, true, "unknown")), Instant.now(), 10L);

        assertThat(snapshot.overallStatus()).isEqualTo(HealthStatus.UP.name());
        assertThat(snapshot.dependencies()).hasSize(1);
    }

    @Test
    void blankOrNullDependencyNamesAreFilteredOut() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                new HealthCheckResult("", HealthStatus.DOWN, Instant.now(), 1L, "bad", "X", true, Map.of()),
                new HealthCheckResult(null, HealthStatus.DOWN, Instant.now(), 1L, "bad", "X", true, Map.of()),
                result("mysql", HealthStatus.UP, true, "ok")), Instant.now(), 10L);

        assertThat(snapshot.dependencies()).hasSize(1);
        assertThat(snapshot.dependencies().get(0).dependencyName()).isEqualTo("mysql");
        assertThat(snapshot.downCount()).isZero();
    }

    @Test
    void totalLatencyIsPropagated() {
        HealthSnapshot snapshot = assembler.assemble(List.of(
                result("mysql", HealthStatus.UP, true, "ok")), Instant.now(), 42L);

        assertThat(snapshot.totalLatencyMs()).isEqualTo(42L);
        assertThat(snapshot.checkedAt()).isNotBlank();
    }
}
