package com.labex.monitor.health.checker;

import com.labex.labexagent.worker.DockerSandboxWorker;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WslSandboxWorker;
import com.labex.monitor.health.HealthCheck;
import com.labex.monitor.health.HealthCheckResult;
import com.labex.monitor.health.MonitorHealthProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Worker 健康检查：只读判断当前激活的沙箱执行环境是否可用。
 * 探活命令是硬编码、只读、白名单命令（docker version / wsl --status），
 * 不接收任何外部输入，也不会从运维页面执行任意 Shell。
 */
@Component
public class WorkerHealthChecker implements HealthCheck {

    public static final String NAME = "worker";
    private static final Logger log = LoggerFactory.getLogger(WorkerHealthChecker.class);

    @FunctionalInterface
    public interface WorkerProbe {
        boolean probe(List<String> command, Duration timeout) throws Exception;
    }

    private final ObjectProvider<SandboxWorker> workerProvider;
    private final MonitorHealthProperties properties;
    private final WorkerProbe probe;

    @Autowired
    public WorkerHealthChecker(ObjectProvider<SandboxWorker> workerProvider, MonitorHealthProperties properties) {
        this(workerProvider, properties, defaultProbe());
    }

    WorkerHealthChecker(ObjectProvider<SandboxWorker> workerProvider, MonitorHealthProperties properties, WorkerProbe probe) {
        this.workerProvider = workerProvider;
        this.properties = properties;
        this.probe = probe;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean affectsCoreService() {
        return true;
    }

    @Override
    public HealthCheckResult check() {
        long start = System.nanoTime();
        try {
            SandboxWorker worker = workerProvider.getIfAvailable();
            if (worker == null) {
                return HealthCheckResult.down(NAME, true, latency(start),
                        "未激活任何沙箱执行环境", "NO_WORKER", Map.of());
            }
            Duration timeout = Duration.ofMillis(Math.max(1, properties.getProbeTimeoutMs()));
            if (worker instanceof DockerSandboxWorker) {
                boolean ok = probe.probe(List.of("docker", "version", "--format", "{{.Server.Version}}"), timeout);
                return ok
                        ? HealthCheckResult.up(NAME, true, latency(start), "Docker 沙箱可用",
                                Map.of("workerType", "docker"))
                        : HealthCheckResult.down(NAME, true, latency(start), "Docker 沙箱不可用",
                                "WORKER_PROBE_FAILED", Map.of("workerType", "docker"));
            }
            if (worker instanceof WslSandboxWorker) {
                boolean ok = probe.probe(List.of("wsl.exe", "--status"), timeout);
                return ok
                        ? HealthCheckResult.up(NAME, true, latency(start), "WSL 沙箱可用",
                                Map.of("workerType", "wsl"))
                        : HealthCheckResult.down(NAME, true, latency(start), "WSL 沙箱不可用",
                                "WORKER_PROBE_FAILED", Map.of("workerType", "wsl"));
            }
            return HealthCheckResult.up(NAME, true, latency(start), "本地执行环境就绪",
                    Map.of("workerType", "local"));
        } catch (Exception e) {
            log.warn("worker health check failed: {}", e.getClass().getSimpleName());
            return HealthCheckResult.down(NAME, true, latency(start), "沙箱探测失败", "WORKER_PROBE_FAILED", Map.of());
        }
    }

    private static WorkerProbe defaultProbe() {
        return (command, timeout) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        };
    }

    private static long latency(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
