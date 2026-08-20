package com.labex.auth.risk;

import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.redis.AuthRedisStore;
import com.labex.auth.redis.AuthRedisUnavailableException;
import com.labex.auth.AuthDigest;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** 登录失败与验证码触发规则，状态只保存在 Redis。 */
@Service
public class LoginRiskService {
    private final AuthRedisStore store;
    private final AuthSecurityProperties properties;

    public LoginRiskService(AuthRedisStore store, AuthSecurityProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public boolean requiresLoginCaptcha(String source, String username) {
        return readCount(failureKey(source, username)) >= properties.getLoginFailureThreshold();
    }

    public boolean requiresRegisterCaptcha(String source) {
        return readCount(registerAttemptKey(source)) >= properties.getRegisterCaptchaThreshold();
    }

    public void recordLoginFailure(String source, String username) {
        increment(failureKey(source, username));
    }

    public void recordRegisterAttempt(String source) {
        increment(registerAttemptKey(source));
    }

    public void clearLoginFailures(String source, String username) {
        try {
            store.delete(failureKey(source, username));
        } catch (AuthRedisUnavailableException ignored) {
            // 成功登录不应因清理失败而变成失败；下次窗口会自然过期。
        }
    }

    private long readCount(String key) {
        try {
            String value = store.get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void increment(String key) {
        store.increment(key, Duration.ofSeconds(Math.max(1, properties.getFailureWindowSeconds())));
    }

    private String failureKey(String source, String username) {
        return "labex:auth:login-fail:" + normalizeSource(source) + ":" + digest(username);
    }

    private String registerAttemptKey(String source) {
        return "labex:auth:register-attempt:" + normalizeSource(source);
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        return source.replaceAll("[^a-zA-Z0-9:._-]", "_");
    }

    private String digest(String value) {
        return AuthDigest.sha256Hex(value);
    }
}
