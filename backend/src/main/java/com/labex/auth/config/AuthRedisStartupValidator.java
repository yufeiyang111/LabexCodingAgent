package com.labex.auth.config;

import com.labex.auth.redis.AuthRedisStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 生产环境拒绝在未配置 Redis 时启动，避免退化为单实例限流。 */
@Component
@Profile({"prod", "production"})
public class AuthRedisStartupValidator {
    public AuthRedisStartupValidator(AuthSecurityProperties properties, AuthRedisStore store) {
        if (properties.getRedisUrl() == null || properties.getRedisUrl().isBlank()) {
            throw new IllegalStateException("LABEX_AGENT_AUTH_REDIS_URL must be configured in production");
        }
        if (!store.ping()) {
            throw new IllegalStateException("Configured authentication Redis is not reachable");
        }
    }
}
