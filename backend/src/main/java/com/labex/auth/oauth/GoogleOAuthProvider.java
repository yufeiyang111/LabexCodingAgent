package com.labex.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GoogleOAuthProvider implements OAuthProviderClient {
    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private final AuthSecurityProperties properties;
    private final OAuthHttpClient http;

    public GoogleOAuthProvider(AuthSecurityProperties properties, OAuthHttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public String provider() {
        return "google";
    }

    @Override
    public boolean enabled() {
        return hasText(properties.getGoogleClientId()) && hasText(properties.getGoogleClientSecret());
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        ensureEnabled();
        return AUTHORIZE_URL + "?client_id=" + OAuthHttpClient.encode(properties.getGoogleClientId())
                + "&redirect_uri=" + OAuthHttpClient.encode(redirectUri)
                + "&response_type=code&access_type=online&prompt=select_account"
                + "&scope=" + OAuthHttpClient.encode("openid email profile")
                + "&state=" + OAuthHttpClient.encode(state);
    }

    @Override
    public OAuthIdentity exchange(String code, String redirectUri) {
        ensureEnabled();
        JsonNode token = http.postForm(TOKEN_URL, Map.of(
                "client_id", properties.getGoogleClientId(),
                "client_secret", properties.getGoogleClientSecret(),
                "code", code,
                "redirect_uri", redirectUri,
                "grant_type", "authorization_code"));
        String accessToken = token.path("access_token").asText("").trim();
        if (accessToken.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "Google 授权失败，请重试");
        }
        JsonNode profile = http.getJson("https://openidconnect.googleapis.com/v1/userinfo", accessToken);
        String subject = profile.path("sub").asText("").trim();
        if (subject.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "未获取到 Google 账号标识");
        }
        String email = profile.path("email_verified").asBoolean(false)
                ? profile.path("email").asText("").trim().toLowerCase() : "";
        return new OAuthIdentity(provider(), subject, email,
                profile.path("name").asText("").trim(),
                profile.path("picture").asText("").trim());
    }

    private void ensureEnabled() {
        if (!enabled()) {
            throw new AuthException(AuthErrorCode.OAUTH_NOT_CONFIGURED, "Google 登录尚未配置");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
