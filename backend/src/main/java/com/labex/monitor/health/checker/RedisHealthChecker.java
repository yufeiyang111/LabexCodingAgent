package com.labex.monitor.health.checker;

import com.labex.auth.redis.AuthRedisStore;
import com.labex.monitor.health.HealthCheck;
import com.labex.monitor.health.HealthCheckResult;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Redis 健康检查：复用认证域 Redis 适配器的只读 ping。 */
@Component
public class RedisHealthChecker implements HealthCheck {

    public static final String NAME = "redis";
    private static final Logger log = LoggerFactory.getLogger(RedisHealthChecker.class);

    private final AuthRedisStore redis;

    public RedisHealthChecker(AuthRedisStore redis) {
        this.redis = redis;
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
            if (redis.ping()) {
                return HealthCheckResult.up(NAME, true, latency(start), "Redis 连接正常", Map.of("probe", "PING"));
            }
            return HealthCheckResult.down(NAME, true, latency(start), "Redis 不可用", "UNREACHABLE", Map.of());
        } catch (Exception e) {
            log.warn("redis health check failed: {}", e.getClass().getSimpleName());
            return HealthCheckResult.down(NAME, true, latency(start), "Redis 不可用", "UNREACHABLE", Map.of());
        }
    }

    private static long latency(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
