package com.labex.monitor.health.checker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.monitor.health.HealthCheckResult;
import com.labex.monitor.health.HealthStatus;
import com.labex.monitor.health.MonitorHealthProperties;
import com.labex.labexagent.worker.DockerSandboxWorker;
import com.labex.labexagent.worker.LocalDevelopmentWorker;
import com.labex.labexagent.worker.WslSandboxWorker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WorkerHealthCheckerTest {

    private final MonitorHealthProperties properties = new MonitorHealthProperties();

    @SuppressWarnings("unchecked")
    private ObjectProvider<com.labex.labexagent.worker.SandboxWorker> providerFor(com.labex.labexagent.worker.SandboxWorker worker) {
        ObjectProvider<com.labex.labexagent.worker.SandboxWorker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(worker);
        return provider;
    }

    private WorkerHealthChecker checker(ObjectProvider<com.labex.labexagent.worker.SandboxWorker> provider,
                                        WorkerHealthChecker.WorkerProbe probe) {
        properties.setProbeTimeoutMs(2000);
        return new WorkerHealthChecker(provider, properties, probe);
    }

    private WorkerHealthChecker.WorkerProbe okProbe() {
        return (command, timeout) -> true;
    }

    @Test
    void noActiveWorkerIsDown() {
        WorkerHealthChecker checker = checker(providerFor(null), okProbe());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.errorCode()).isEqualTo("NO_WORKER");
        assertThat(result.affectsCoreService()).isTrue();
    }

    @Test
    void dockerWorkerProbeOkIsUp() {
        DockerSandboxWorker worker = mock(DockerSandboxWorker.class);
        WorkerHealthChecker checker = checker(providerFor(worker), okProbe());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.name()).isEqualTo("worker");
    }

    @Test
    void dockerWorkerProbeFailIsDown() {
        DockerSandboxWorker worker = mock(DockerSandboxWorker.class);
        WorkerHealthChecker checker = checker(providerFor(worker), (command, timeout) -> false);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.errorCode()).isEqualTo("WORKER_PROBE_FAILED");
    }

    @Test
    void wslWorkerProbeOkIsUp() {
        WslSandboxWorker worker = mock(WslSandboxWorker.class);
        WorkerHealthChecker checker = checker(providerFor(worker), okProbe());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void localWorkerIsAlwaysUp() {
        LocalDevelopmentWorker worker = mock(LocalDevelopmentWorker.class);
        WorkerHealthChecker checker = checker(providerFor(worker), okProbe());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void probeExceptionIsIsolatedWithoutLeakingCommand() {
        DockerSandboxWorker worker = mock(DockerSandboxWorker.class);
        WorkerHealthChecker checker = checker(providerFor(worker), (command, timeout) -> {
            throw new IllegalStateException("docker -H tcp://secret:2375");
        });

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.safeMessage()).doesNotContain("tcp://");
        assertThat(result.safeMessage()).doesNotContain("secret");
    }

    @Test
    void probeReceivesBoundedTimeout() {
        DockerSandboxWorker worker = mock(DockerSandboxWorker.class);
        Duration[] captured = new Duration[1];
        WorkerHealthChecker checker = checker(providerFor(worker), (command, timeout) -> {
            captured[0] = timeout;
            return true;
        });

        checker.check();

        assertThat(captured[0]).isEqualTo(Duration.ofMillis(2000));
        assertThat(captured[0]).isLessThanOrEqualTo(Duration.ofSeconds(30));
    }
}
