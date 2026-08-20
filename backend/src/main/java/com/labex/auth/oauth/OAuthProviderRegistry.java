package com.labex.auth.oauth;

import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Provider 注册表，只暴露已配置的第三方登录方式。 */
@Component
public class OAuthProviderRegistry {
    private final Map<String, OAuthProviderClient> providers;

    public OAuthProviderRegistry(List<OAuthProviderClient> clients) {
        this.providers = clients.stream().collect(Collectors.toUnmodifiableMap(
                client -> client.provider().toLowerCase(), Function.identity()));
    }

    public List<String> enabledProviders() {
        return providers.values().stream()
                .filter(OAuthProviderClient::enabled)
                .map(OAuthProviderClient::provider)
                .sorted()
                .toList();
    }

    public OAuthProviderClient require(String provider) {
        OAuthProviderClient client = providers.get(provider == null ? "" : provider.toLowerCase());
        if (client == null || !client.enabled()) {
            throw new AuthException(AuthErrorCode.OAUTH_NOT_CONFIGURED, "该第三方登录方式暂未配置");
        }
        return client;
    }
}
