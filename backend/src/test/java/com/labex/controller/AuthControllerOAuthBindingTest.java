package com.labex.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.auth.AuthApplicationService;
import com.labex.auth.captcha.CaptchaService;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.oauth.OAuthApplicationService.AuthorizationRequest;
import com.labex.common.Result;
import com.labex.service.AuthService;
import org.springframework.http.HttpHeaders;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AuthControllerOAuthBindingTest {
    @Test
    void authenticatedBindingStartReturnsAuthorizationUrlAndStoresStateInHttpOnlyCookie() {
        com.labex.auth.oauth.OAuthApplicationService oauthService = mock(com.labex.auth.oauth.OAuthApplicationService.class);
        when(oauthService.bindingAuthorizationUrl(eq("github"), eq(42)))
                .thenReturn(new AuthorizationRequest("state-1", "https://github.example.test/authorize"));
        AuthController controller = new AuthController(mock(AuthApplicationService.class), mock(AuthService.class),
                mock(CaptchaService.class), oauthService, new AuthSecurityProperties());
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Map<String, Object>> result = controller.oauthBindStart("github",
                new UsernamePasswordAuthenticationToken("42", "unused"), new MockHttpServletRequest(), response);

        assertEquals(0, result.getCode());
        assertEquals("https://github.example.test/authorize", result.getData().get("authorizationUrl"));
        String stateCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(stateCookie.contains("labex_oauth_state=state-1"));
        assertTrue(stateCookie.contains("HttpOnly"));
    }
}
