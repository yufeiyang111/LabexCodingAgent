package com.labex.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GitHubOAuthProvider implements OAuthProviderClient {
    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private final AuthSecurityProperties properties;
    private final OAuthHttpClient http;

    public GitHubOAuthProvider(AuthSecurityProperties properties, OAuthHttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public String provider() {
        return "github";
    }

    @Override
    public boolean enabled() {
        return hasText(properties.getGithubClientId()) && hasText(properties.getGithubClientSecret());
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        ensureEnabled();
        return AUTHORIZE_URL + "?client_id=" + OAuthHttpClient.encode(properties.getGithubClientId())
                + "&redirect_uri=" + OAuthHttpClient.encode(redirectUri)
                + "&scope=" + OAuthHttpClient.encode("read:user user:email")
                + "&state=" + OAuthHttpClient.encode(state);
    }

    @Override
    public OAuthIdentity exchange(String code, String redirectUri) {
        ensureEnabled();
        JsonNode token = http.postForm(TOKEN_URL, Map.of(
                "client_id", properties.getGithubClientId(),
                "client_secret", properties.getGithubClientSecret(),
                "code", code,
                "redirect_uri", redirectUri));
        String accessToken = text(token, "access_token");
        if (accessToken.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "GitHub 授权失败，请重试");
        }
        JsonNode profile = http.getJson("https://api.github.com/user", accessToken);
        JsonNode emails = http.getJson("https://api.github.com/user/emails", accessToken);
        String email = firstVerifiedEmail(emails);
        String subject = text(profile, "id");
        if (subject.isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "未获取到 GitHub 账号标识");
        }
        String name = text(profile, "name");
        if (name.isBlank()) {
            name = text(profile, "login");
        }
        return new OAuthIdentity(provider(), subject, email, name, text(profile, "avatar_url"));
    }

    private String firstVerifiedEmail(JsonNode emails) {
        if (emails != null && emails.isArray()) {
            for (JsonNode item : emails) {
                if (item.path("verified").asBoolean(false) && item.path("email").isTextual()) {
                    return item.path("email").asText().trim().toLowerCase();
                }
            }
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        return node == null || node.path(field).isMissingNode() ? "" : node.path(field).asText("").trim();
    }

    private void ensureEnabled() {
        if (!enabled()) {
            throw new AuthException(AuthErrorCode.OAUTH_NOT_CONFIGURED, "GitHub 登录尚未配置");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
