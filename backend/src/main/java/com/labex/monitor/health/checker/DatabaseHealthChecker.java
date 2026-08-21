package com.labex.monitor.health.checker;

import com.labex.monitor.health.HealthCheck;
import com.labex.monitor.health.HealthCheckResult;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** MySQL 健康检查：执行 SELECT 1 探活。 */
@Component
public class DatabaseHealthChecker implements HealthCheck {

    public static final String NAME = "mysql";
    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthChecker.class);

    private final JdbcTemplate jdbc;

    public DatabaseHealthChecker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            if (value == null) {
                return HealthCheckResult.down(NAME, true, latency(start), "MySQL 返回异常", "BAD_RESPONSE", Map.of());
            }
            return HealthCheckResult.up(NAME, true, latency(start), "MySQL 连接正常", Map.of("probe", "SELECT 1"));
        } catch (Exception e) {
            // 只记录异常类型，避免把连接串/凭据写进日志。
            log.warn("mysql health check failed: {}", e.getClass().getSimpleName());
            return HealthCheckResult.down(NAME, true, latency(start), "MySQL 不可用", "UNREACHABLE", Map.of());
        }
    }

    private static long latency(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
