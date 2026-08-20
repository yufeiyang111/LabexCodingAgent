package com.labex.auth.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AuthRateLimitServiceTest {
    @Test
    void rejectsRequestsAfterConfiguredLimit() {
        AuthRedisStore store = Mockito.mock(AuthRedisStore.class);
        when(store.increment(anyString(), any(Duration.class))).thenReturn(3L);
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setLoginRateLimit(2);

        AuthRateLimitService service = new AuthRateLimitService(store, properties);

        assertThrows(AuthException.class, () -> service.checkLogin("ip:user"));
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        AuthRedisStore store = Mockito.mock(AuthRedisStore.class);
        when(store.increment(anyString(), any(Duration.class)))
                .thenThrow(new AuthRedisUnavailableException(new IllegalStateException("offline")));

        AuthRateLimitService service = new AuthRateLimitService(store, new AuthSecurityProperties());

        assertThrows(AuthException.class, () -> service.checkRegister("ip"));
    }
}
