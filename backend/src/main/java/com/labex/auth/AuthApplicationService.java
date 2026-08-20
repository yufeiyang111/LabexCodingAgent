package com.labex.auth;

import com.labex.auth.captcha.CaptchaService;
import com.labex.auth.redis.AuthRateLimitService;
import com.labex.auth.risk.LoginRiskService;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.service.AuthService;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 登录/注册用例编排器，Controller 不承载风险和凭证规则。 */
@Service
public class AuthApplicationService {
    private final AuthService authService;
    private final CaptchaService captchaService;
    private final LoginRiskService riskService;
    private final AuthRateLimitService rateLimitService;
    private final AuthSecurityProperties properties;

    public AuthApplicationService(AuthService authService, CaptchaService captchaService,
                                 LoginRiskService riskService, AuthRateLimitService rateLimitService,
                                 AuthSecurityProperties properties) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.riskService = riskService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    public Map<String, Object> register(AuthCommand command, String source) {
        String normalizedSource = normalizeSource(source);
        rateLimitService.checkRegister(normalizedSource);
        if (properties.isInviteCodeEnabled()) {
            throw new AuthException(AuthErrorCode.REGISTRATION_DISABLED, "公网注册已关闭，请使用邀请码注册");
        }
        if (riskService.requiresRegisterCaptcha(normalizedSource)) {
            requireCaptcha(command);
            captchaService.verify("register", command.captchaId(), command.captchaCode());
        }
        riskService.recordRegisterAttempt(normalizedSource);
        return authService.register(command.username(), command.email(), command.password(), command.displayName());
    }

    public Map<String, Object> inviteRegister(AuthCommand command, String inviteCode, String source) {
        String normalizedSource = normalizeSource(source);
        rateLimitService.checkRegister(normalizedSource);
        if (!properties.isInviteCodeEnabled()) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "邀请码注册未开启，请使用普通注册");
        }
        if (inviteCode == null || inviteCode.isBlank() || !isValidInviteCode(inviteCode)) {
            riskService.recordRegisterAttempt(normalizedSource);
            throw new AuthException(AuthErrorCode.INVALID_INVITE_CODE, "邀请码无效或已过期");
        }
        if (riskService.requiresRegisterCaptcha(normalizedSource)) {
            requireCaptcha(command);
            captchaService.verify("register", command.captchaId(), command.captchaCode());
        }
        riskService.recordRegisterAttempt(normalizedSource);
        return authService.register(command.username(), command.email(), command.password(), command.displayName());
    }

    private boolean isValidInviteCode(String inputCode) {
        if (inputCode == null || inputCode.isBlank()) return false;
        String configured = properties.getInviteCodes();
        if (configured == null || configured.isBlank()) return false;
        String trimmedInput = inputCode.trim();
        for (String code : configured.split(",")) {
            if (code.trim().equalsIgnoreCase(trimmedInput)) {
                return true;
            }
        }
        return false;
    }

    public boolean isInviteCodeEnabled() {
        return properties.isInviteCodeEnabled();
    }

    public Map<String, Object> login(AuthCommand command, String source) {
        String normalizedSource = normalizeSource(source);
        String username = command.username() == null ? "" : command.username().trim().toLowerCase();
        rateLimitService.checkLogin(normalizedSource + ":" + digest(username));
        if (riskService.requiresLoginCaptcha(normalizedSource, username)) {
            requireCaptcha(command);
            captchaService.verify("login", command.captchaId(), command.captchaCode());
        }
        try {
            Map<String, Object> response = authService.login(command.username(), command.password());
            riskService.clearLoginFailures(normalizedSource, username);
            return response;
        } catch (AuthException failure) {
            if (failure.getCode() == AuthErrorCode.INVALID_CREDENTIALS) {
                riskService.recordLoginFailure(normalizedSource, username);
            }
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, safeValidationMessage());
        }
    }

    public void checkOAuthExchange(String source) {
        rateLimitService.check("oauth-exchange", normalizeSource(source), properties.getOauthExchangeRateLimit());
    }

    private void requireCaptcha(AuthCommand command) {
        if (command.captchaId() == null || command.captchaId().isBlank()
                || command.captchaCode() == null || command.captchaCode().isBlank()) {
            throw new AuthException(AuthErrorCode.CAPTCHA_REQUIRED, "请先完成图形验证码");
        }
    }

    private String safeValidationMessage() {
        return "认证信息格式不正确";
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
