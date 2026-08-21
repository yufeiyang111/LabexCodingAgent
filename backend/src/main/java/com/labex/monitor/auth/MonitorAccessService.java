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
    private final MonitorRoleService roleService;

    public MonitorAccessService(MonitorProperties properties, AuthRedisStore redisStore,
                                MonitorRoleService roleService) {
        this.properties = properties;
        this.redisStore = redisStore;
        this.roleService = roleService;
    }

    public boolean isEnabled() {
        return properties.isEnabled() && properties.getAccessCode() != null && !properties.getAccessCode().isBlank();
    }

    /** 校验访问码并返回应签发的角色：操作码命中则 OPERATOR，否则只读 VIEWER。 */
    public String roleForCode(String input) {
        String operatorCode = properties.getOperatorCode();
        if (operatorCode != null && !operatorCode.isBlank() && operatorCode.equals(input)) {
            return MonitorRoleService.OPERATOR;
        }
        return MonitorRoleService.VIEWER;
    }

    /** 校验访问码；连续失败触发 Redis 限流，成功后重置计数。 */
    /** 校验操作码（操作者校验码）；供会话登录与按请求提权复用。 */
    public boolean verifyOperatorCode(String input) {
        String operatorCode = properties.getOperatorCode();
        if (operatorCode == null || operatorCode.isBlank()) {
            return false;
        }
        return constantTimeEquals(operatorCode, input);
    }

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
        return issueSession(MonitorRoleService.VIEWER);
    }

    public String issueSession(String role) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisStore.put(SESSION_KEY_PREFIX + token,
                roleService.normalize(role) + "|" + LocalDateTime.now(),
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

    /** 解析会话角色；旧格式会话（纯时间戳）按只读处理。 */
    public String sessionRole(String token) {
        if (token == null || token.isBlank()) {
            return MonitorRoleService.VIEWER;
        }
        try {
            String value = redisStore.get(SESSION_KEY_PREFIX + token);
            if (value == null) {
                return MonitorRoleService.VIEWER;
            }
            int separator = value.indexOf('|');
            if (separator < 0) {
                return MonitorRoleService.VIEWER;
            }
            return roleService.normalize(value.substring(0, separator));
        } catch (com.labex.auth.redis.AuthRedisUnavailableException unavailable) {
            return MonitorRoleService.VIEWER;
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