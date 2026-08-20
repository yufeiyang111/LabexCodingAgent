package com.labex.monitor.auth;

import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.redis.AuthRedisStore;
import com.labex.monitor.config.MonitorProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 监控页访问控制：校验码验证、会话 token 签发/校验/撤销，复用认证域 Redis 原子计数限流。 */
@Service
public class MonitorAccessService {

    private static final String SESSION_KEY_PREFIX = "labex:monitor:session:";
    private static final String AUTH_RATE_KEY_PREFIX = "labex:monitor:auth:rate:";

    private final MonitorProperties properties;
    private final AuthRedisStore redisStore;

    public MonitorAccessService(MonitorProperties properties, AuthRedisStore redisStore) {
        this.properties = properties;
        this.redisStore = redisStore;
    }

    public boolean isEnabled() {
        return properties.isEnabled() && properties.getAccessCode() != null && !properties.getAccessCode().isBlank();
    }

    /** 校验访问码；连续失败触发 Redis 限流，成功后重置计数。 */
    public boolean verifyAccessCode(String input, String clientIp) {
        if (!isEnabled()) {
            return false;
        }
        long attempts = redisStore.increment(
                AUTH_RATE_KEY_PREFIX + clientIp,
                Duration.ofSeconds(properties.getAuthFailureWindowSeconds()));
        if (attempts > properties.getAuthRateLimit()) {
            throw new AuthException(AuthErrorCode.RATE_LIMITED, "校验码尝试次数过多，请稍后再试",
                    Map.of("retryAfterSeconds", properties.getAuthFailureWindowSeconds()),
                    properties.getAuthFailureWindowSeconds());
        }
        boolean matched = constantTimeEquals(properties.getAccessCode(), input);
        if (matched) {
            redisStore.delete(AUTH_RATE_KEY_PREFIX + clientIp);
        }
        return matched;
    }

    public String issueSession() {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisStore.put(SESSION_KEY_PREFIX + token, LocalDateTime.now().toString(),
                Duration.ofHours(properties.getSessionTtlHours()));
        return token;
    }

    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            return redisStore.get(SESSION_KEY_PREFIX + token) != null;
        } catch (com.labex.auth.redis.AuthRedisUnavailableException unavailable) {
            return false;
        }
    }

    public void revokeSession(String token) {
        if (token != null && !token.isBlank()) {
            redisStore.delete(SESSION_KEY_PREFIX + token);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}