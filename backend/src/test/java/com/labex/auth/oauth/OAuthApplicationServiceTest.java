package com.labex.auth.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.redis.AuthRedisStore;
import com.labex.entity.UserOAuthBinding;
import com.labex.mapper.UserOAuthBindingMapper;
import com.labex.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class OAuthApplicationServiceTest {
    @Mock
    private OAuthProviderRegistry registry;
    @Mock
    private AuthRedisStore redis;
    @Mock
    private AuthService authService;
    @Mock
    private UserOAuthBindingMapper bindingMapper;

    private OAuthApplicationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OAuthApplicationService(registry, redis, new AuthSecurityProperties(),
                new ObjectMapper(), authService, bindingMapper);
    }

    @Test
    void unboundOAuthAccountIsRejectedWithoutCreatingLocalUser() {
        when(redis.consume(anyString()))
                .thenReturn("{\"provider\":\"github\",\"subject\":\"subject-1\","
                        + "\"email\":\"new@example.com\",\"displayName\":\"New User\","
                        + "\"avatarUrl\":null}");
        when(bindingMapper.selectOne(any())).thenReturn(null);

        AuthException failure = assertThrows(AuthException.class, () -> service.exchange("code-1"));

        assertEquals(AuthErrorCode.OAUTH_ACCOUNT_NOT_BOUND, failure.getCode());
        verify(authService, never()).issueLoginResponseForUser(any());
    }

    @Test
    void exchangeUsesExistingBindingOnly() {
        UserOAuthBinding binding = new UserOAuthBinding();
        binding.setUserId(42);
        when(redis.consume(anyString()))
                .thenReturn("{\"provider\":\"github\",\"subject\":\"subject-2\","
                        + "\"email\":\"known@example.com\",\"displayName\":\"Known User\","
                        + "\"avatarUrl\":null}");
        when(bindingMapper.selectOne(any())).thenReturn(binding);

        service.exchange("code-2");

        verify(authService).getById(42);
        verify(authService).issueLoginResponseForUser(any());
    }

    @Test
    void bindingErrorRedirectFallsBackToProjectsAlongsideConfiguredLoginCallback() {
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setOauthFrontendCallback("https://labexagent.123845.xyz/login");
        properties.setOauthBindingFrontendCallback("");
        OAuthApplicationService configuredService = new OAuthApplicationService(registry, redis, properties,
                new ObjectMapper(), authService, bindingMapper);

        assertEquals("https://labexagent.123845.xyz/projects?oauth_error=-1001",
                configuredService.errorRedirect(new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "请先登录本地账号")));
    }
}
