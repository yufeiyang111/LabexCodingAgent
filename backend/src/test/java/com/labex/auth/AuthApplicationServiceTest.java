package com.labex.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.auth.captcha.CaptchaService;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.redis.AuthRateLimitService;
import com.labex.auth.redis.AuthRedisUnavailableException;
import com.labex.auth.risk.LoginRiskService;
import com.labex.service.AuthService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AuthApplicationServiceTest {
    @Mock
    private AuthService authService;
    @Mock
    private CaptchaService captchaService;
    @Mock
    private LoginRiskService riskService;
    @Mock
    private AuthRateLimitService rateLimitService;
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AuthApplicationService(authService, captchaService, riskService, rateLimitService,
                new AuthSecurityProperties());
    }

    @Test
    void loginRequiresCaptchaAfterRiskThreshold() {
        when(riskService.requiresLoginCaptcha("127.0.0.1", "alice")).thenReturn(true);

        assertThrows(AuthException.class, () -> service.login(
                AuthCommand.login("alice", "secret", null, null), "127.0.0.1"));

        verify(rateLimitService).checkLogin(eq("127.0.0.1:" + digest("alice")));
        verify(captchaService, never()).verify(any(), any(), any());
        verify(authService, never()).login(any(), any());
    }

    @Test
    void successfulRegistrationDelegatesOptionalEmail() {
        Map<String, Object> response = Map.of("token", "token");
        when(authService.register("alice", "alice@example.com", "secret", "Alice")).thenReturn(response);

        Map<String, Object> result = service.register(
                AuthCommand.register("alice", "alice@example.com", "Alice", "secret", null, null),
                "127.0.0.1");

        org.junit.jupiter.api.Assertions.assertSame(response, result);
        verify(authService).register("alice", "alice@example.com", "secret", "Alice");
    }

    @Test
    void loginRiskReadFailsClosedWhenRedisIsUnavailable() {
        when(riskService.requiresLoginCaptcha("127.0.0.1", "alice"))
                .thenThrow(new AuthRedisUnavailableException(new IllegalStateException("offline")));

        assertThrows(AuthRedisUnavailableException.class, () -> service.login(
                AuthCommand.login("alice", "secret", null, null), "127.0.0.1"));
        verify(authService, never()).login(any(), any());
    }

    @Test
    void normalRegistrationThrowsWhenInviteCodeIsEnabled() {
        AuthSecurityProperties props = new AuthSecurityProperties();
        props.setInviteCodeEnabled(true);
        AuthApplicationService inviteService = new AuthApplicationService(
                authService, captchaService, riskService, rateLimitService, props);

        AuthException ex = assertThrows(AuthException.class, () -> inviteService.register(
                AuthCommand.register("alice", "alice@example.com", "Alice", "secret", null, null),
                "127.0.0.1"));
        org.junit.jupiter.api.Assertions.assertEquals(AuthErrorCode.REGISTRATION_DISABLED, ex.getCode());
        verify(authService, never()).register(any(), any(), any(), any());
    }

    @Test
    void inviteRegisterSucceedsWithValidInviteCode() {
        AuthSecurityProperties props = new AuthSecurityProperties();
        props.setInviteCodeEnabled(true);
        props.setInviteCodes("VIP-2026,SECRET-CODE");
        AuthApplicationService inviteService = new AuthApplicationService(
                authService, captchaService, riskService, rateLimitService, props);

        Map<String, Object> response = Map.of("token", "token-xyz");
        when(authService.register("bob", "bob@example.com", "secret", "Bob")).thenReturn(response);

        Map<String, Object> result = inviteService.inviteRegister(
                AuthCommand.register("bob", "bob@example.com", "Bob", "secret", null, null),
                "vip-2026",
                "127.0.0.1");

        org.junit.jupiter.api.Assertions.assertSame(response, result);
        verify(authService).register("bob", "bob@example.com", "secret", "Bob");
    }

    @Test
    void inviteRegisterRejectsInvalidInviteCode() {
        AuthSecurityProperties props = new AuthSecurityProperties();
        props.setInviteCodeEnabled(true);
        props.setInviteCodes("VIP-2026");
        AuthApplicationService inviteService = new AuthApplicationService(
                authService, captchaService, riskService, rateLimitService, props);

        AuthException ex = assertThrows(AuthException.class, () -> inviteService.inviteRegister(
                AuthCommand.register("bob", "bob@example.com", "Bob", "secret", null, null),
                "wrong-code",
                "127.0.0.1"));
        org.junit.jupiter.api.Assertions.assertEquals(AuthErrorCode.INVALID_INVITE_CODE, ex.getCode());
        verify(authService, never()).register(any(), any(), any(), any());
    }

    private static String digest(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
