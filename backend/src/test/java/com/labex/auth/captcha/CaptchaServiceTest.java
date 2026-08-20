package com.labex.auth.captcha;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.redis.AuthRateLimitService;
import com.labex.auth.redis.AuthRedisStore;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class CaptchaServiceTest {
    @Mock
    private AuthRedisStore store;
    @Mock
    private AuthRateLimitService rateLimit;
    private CaptchaService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setCaptchaLength(5);
        service = new CaptchaService(store, rateLimit, properties);
    }

    @Test
    void createsAnImageAndStoresOnlyAHashedValue() {
        Map<String, Object> result = service.create("login", "127.0.0.1");

        assertTrue(String.valueOf(result.get("image")).startsWith("data:image/png;base64,"));
        verify(store).put(anyString(), anyString(), any(Duration.class));
        verify(rateLimit).checkCaptcha("127.0.0.1");
    }

    @Test
    void rejectsMissingOrConsumedCaptcha() {
        when(store.consume(anyString())).thenReturn(null);

        assertThrows(AuthException.class, () -> service.verify("login", "id", "abcde"));
        verify(store).consume("labex:auth:captcha:id");
    }
}
