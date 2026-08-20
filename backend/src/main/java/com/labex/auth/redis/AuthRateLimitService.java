package com.labex.auth.redis;

import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 认证接口统一限流入口。 */
@Service
public class AuthRateLimitService {
    private final AuthRedisStore store;
    private final AuthSecurityProperties properties;

    public AuthRateLimitService(AuthRedisStore store, AuthSecurityProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public void check(String scope, String discriminator, int limit) {
        String key = "labex:auth:rate:" + scope + ":" + discriminator;
        long count;
        try {
            count = store.increment(key, Duration.ofSeconds(Math.max(1, properties.getFailureWindowSeconds())));
        } catch (AuthRedisUnavailableException failure) {
            throw new AuthException(AuthErrorCode.REDIS_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
        }
        if (count > Math.max(1, limit)) {
            long retry = Math.max(1, properties.getFailureWindowSeconds());
            throw new AuthException(AuthErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试",
                    Map.of("retryAfterSeconds", retry), retry);
        }
    }

    public void checkLogin(String discriminator) {
        check("login", discriminator, properties.getLoginRateLimit());
    }

    public void checkRegister(String discriminator) {
        check("register", discriminator, properties.getRegisterRateLimit());
    }

    public void checkCaptcha(String discriminator) {
        check("captcha", discriminator, properties.getCaptchaRateLimit());
    }
}
