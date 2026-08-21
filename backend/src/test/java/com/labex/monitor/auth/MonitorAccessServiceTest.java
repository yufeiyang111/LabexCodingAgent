package com.labex.monitor.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.auth.AuthException;
import com.labex.auth.redis.AuthRedisStore;
import com.labex.monitor.config.MonitorProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MonitorAccessServiceTest {

    private final AuthRedisStore redisStore = mock(AuthRedisStore.class);

    private MonitorProperties properties(String accessCode) {
        MonitorProperties props = new MonitorProperties();
        props.setEnabled(true);
        props.setAccessCode(accessCode);
        props.setAuthRateLimit(5);
        props.setAuthFailureWindowSeconds(300);
        props.setSessionTtlHours(24);
        return props;
    }

    @Test
    void acceptsCorrectAccessCodeAndResetsRateLimit() {
        MonitorAccessService service = new MonitorAccessService(properties("s3cret"), redisStore, new MonitorRoleService());
        when(redisStore.increment(anyString(), any(Duration.class))).thenReturn(1L);

        boolean ok = service.verifyAccessCode("s3cret", "1.2.3.4");

        assertThat(ok).isTrue();
        verify(redisStore).delete("labex:monitor:auth:rate:1.2.3.4");
    }

    @Test
    void rejectsWrongAccessCodeWithoutReset() {
        MonitorAccessService service = new MonitorAccessService(properties("s3cret"), redisStore, new MonitorRoleService());
        when(redisStore.increment(anyString(), any(Duration.class))).thenReturn(1L);

        assertThat(service.verifyAccessCode("wrong", "1.2.3.4")).isFalse();
        verify(redisStore, never()).delete("labex:monitor:auth:rate:1.2.3.4");
    }

    @Test
    void disabledWhenAccessCodeBlank() {
        MonitorAccessService service = new MonitorAccessService(properties(""), redisStore, new MonitorRoleService());

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.verifyAccessCode("anything", "1.2.3.4")).isFalse();
        verify(redisStore, never()).increment(anyString(), any(Duration.class));
    }

    @Test
    void throwsRateLimitedWhenAttemptsExceedLimit() {
        MonitorAccessService service = new MonitorAccessService(properties("s3cret"), redisStore, new MonitorRoleService());
        when(redisStore.increment(anyString(), any(Duration.class))).thenReturn(6L);

        assertThatThrownBy(() -> service.verifyAccessCode("s3cret", "1.2.3.4"))
                .isInstanceOf(AuthException.class)
                .satisfies(e -> assertThat(((AuthException) e).getRetryAfterSeconds()).isEqualTo(300));
    }

    @Test
    void sessionTokenRoundTrip() {
        MonitorAccessService service = new MonitorAccessService(properties("s3cret"), redisStore, new MonitorRoleService());
        when(redisStore.get(anyString())).thenReturn("present");

        String token = service.issueSession();
        assertThat(token).isNotBlank();
        assertThat(service.isValidToken(token)).isTrue();
        verify(redisStore).put(anyString(), anyString(), any(Duration.class));

        when(redisStore.get(anyString())).thenReturn(null);
        assertThat(service.isValidToken(token)).isFalse();
        assertThat(service.isValidToken(null)).isFalse();
        assertThat(service.isValidToken("   ")).isFalse();
    }

    @Test
    void revokeSessionDeletesToken() {
        MonitorAccessService service = new MonitorAccessService(properties("s3cret"), redisStore, new MonitorRoleService());

        service.revokeSession("token-abc");
        verify(redisStore).delete("labex:monitor:session:token-abc");
        service.revokeSession(null);
        service.revokeSession("");
    }
}
