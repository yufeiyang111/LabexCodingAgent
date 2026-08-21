package com.labex.monitor.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.auth.redis.AuthRedisStore;
import com.labex.monitor.health.checker.RedisHealthChecker;
import org.junit.jupiter.api.Test;

class RedisHealthCheckerTest {

    private final AuthRedisStore redis = mock(AuthRedisStore.class);
    private final RedisHealthChecker checker = new RedisHealthChecker(redis);

    @Test
    void pingOkIsUp() {
        when(redis.ping()).thenReturn(true);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.name()).isEqualTo("redis");
        assertThat(result.affectsCoreService()).isTrue();
    }

    @Test
    void pingFailIsDown() {
        when(redis.ping()).thenReturn(false);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.errorCode()).isEqualTo("UNREACHABLE");
    }

    @Test
    void pingExceptionIsDownWithoutLeakingStack() {
        when(redis.ping()).thenThrow(new IllegalStateException("redis://:token@127.0.0.1:6379"));

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.safeMessage()).doesNotContain("token");
        assertThat(result.safeMessage()).doesNotContain("redis://");
        assertThat(result.safeMessage()).doesNotContain("IllegalStateException");
    }
}
