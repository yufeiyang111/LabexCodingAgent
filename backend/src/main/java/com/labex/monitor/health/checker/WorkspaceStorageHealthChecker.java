package com.labex.monitor.health.checker;

import com.labex.monitor.health.HealthCheck;
import com.labex.monitor.health.HealthCheckResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Workspace 存储健康检查：探测工作区根目录存在性、读写性，并写入后删除临时探针文件。 */
@Component
public class WorkspaceStorageHealthChecker implements HealthCheck {

    public static final String NAME = "workspace";
    private static final String PROBE_PREFIX = ".health-probe-";
    private static final Logger log = LoggerFactory.getLogger(WorkspaceStorageHealthChecker.class);

    private final String workspaceBasePath;

    public WorkspaceStorageHealthChecker(
            @Value("${labex-agent.project-base-path:}") String workspaceBasePath) {
        this.workspaceBasePath = workspaceBasePath;
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
            if (workspaceBasePath == null || workspaceBasePath.isBlank()) {
                return HealthCheckResult.down(NAME, true, latency(start), "工作区根目录未配置", "NOT_CONFIGURED", Map.of());
            }
            Path root = Path.of(workspaceBasePath).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                return HealthCheckResult.down(NAME, true, latency(start), "工作区根目录不存在", "MISSING", Map.of());
            }
            if (!Files.isDirectory(root)) {
                return HealthCheckResult.down(NAME, true, latency(start), "工作区路径不是目录", "INVALID", Map.of());
            }
            if (!Files.isReadable(root) || !Files.isWritable(root)) {
                return HealthCheckResult.down(NAME, true, latency(start), "工作区目录不可读写", "PERMISSION", Map.of());
            }
            Path probe = Files.createTempFile(root, PROBE_PREFIX, ".tmp");
            try {
                Files.writeString(probe, "ok", StandardCharsets.UTF_8);
                return HealthCheckResult.up(NAME, true, latency(start), "工作区存储可读写",
                        Map.of("base", sanitize(root)));
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (Exception e) {
            log.warn("workspace health check failed: {}", e.getClass().getSimpleName());
            return HealthCheckResult.down(NAME, true, latency(start), "工作区存储不可用", "WRITE_FAILED", Map.of());
        }
    }

    private static String sanitize(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? "workspace" : fileName.toString();
    }

    private static long latency(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
