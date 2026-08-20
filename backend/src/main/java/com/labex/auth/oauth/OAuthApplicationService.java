package com.labex.auth.oauth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.redis.AuthRedisStore;
import com.labex.entity.AppUser;
import com.labex.entity.UserOAuthBinding;
import com.labex.mapper.UserOAuthBindingMapper;
import com.labex.service.AuthService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** GitHub/Google 登录、一次性 code 和账号绑定的认证域编排器。 */
@Service
public class OAuthApplicationService {
    private static final String PURPOSE_LOGIN = "LOGIN";
    private static final String PURPOSE_BIND = "BIND";
    private static final List<String> SUPPORTED_PROVIDERS = List.of("github", "google");

    private final SecureRandom random = new SecureRandom();
    private final OAuthProviderRegistry registry;
    private final AuthRedisStore redis;
    private final AuthSecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final UserOAuthBindingMapper bindingMapper;

    public OAuthApplicationService(OAuthProviderRegistry registry, AuthRedisStore redis,
                                   AuthSecurityProperties properties, ObjectMapper objectMapper,
                                   AuthService authService, UserOAuthBindingMapper bindingMapper) {
        this.registry = registry;
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.bindingMapper = bindingMapper;
    }

    public Map<String, Object> enabledProviders() {
        return Map.of("providers", registry.enabledProviders());
    }

    public AuthorizationRequest authorizationUrl(String provider) {
        return authorizationUrl(provider, PURPOSE_LOGIN, null);
    }

    public AuthorizationRequest bindingAuthorizationUrl(String provider, Integer userId) {
        if (userId == null || !isActiveUser(userId)) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "本地账号不可用");
        }
        String normalizedProvider = normalizeProvider(provider);
        if (findBindingByUserProvider(userId, normalizedProvider) != null) {
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ALREADY_BOUND, "该登录方式已经绑定");
        }
        return authorizationUrl(normalizedProvider, PURPOSE_BIND, userId);
    }

    private AuthorizationRequest authorizationUrl(String provider, String purpose, Integer userId) {
        OAuthProviderClient client = registry.require(provider);
        String state = randomToken(24);
        OAuthStatePayload payload = new OAuthStatePayload(purpose, client.provider(), userId);
        saveState(state, payload);
        return new AuthorizationRequest(state, client.authorizationUrl(state, callbackUri(client.provider())));
    }

    @Transactional
    public String callback(String provider, String state, String code) {
        OAuthProviderClient client = registry.require(provider);
        if (!StringUtils.hasText(state) || !StringUtils.hasText(code)) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "第三方授权参数不完整，请重试");
        }
        OAuthStatePayload statePayload = consumeState(state, client.provider());
        OAuthIdentity identity = client.exchange(code, callbackUri(client.provider()));
        if (PURPOSE_BIND.equals(statePayload.purpose())) {
            if (statePayload.userId() == null) {
                throw new AuthException(AuthErrorCode.OAUTH_BIND_STATE_INVALID, "第三方绑定状态无效，请重新发起");
            }
            bindIdentity(statePayload.userId(), identity);
            return redirectWithQuery(bindingFrontendCallback(),
                    "oauth_bound", identity.provider(), null);
        }
        String oauthCode = randomToken(24);
        saveCode(codeKey(oauthCode), identity);
        return redirectWithQuery(properties.getOauthFrontendCallback(), "oauth_code", oauthCode, null);
    }

    @Transactional
    public Map<String, Object> exchange(String oauthCode) {
        OAuthCodePayload payload = consumeCode(codeKey(oauthCode), AuthErrorCode.OAUTH_CODE_INVALID,
                "第三方登录凭证无效或已过期");
        UserOAuthBinding binding = findBinding(payload.provider(), payload.subject());
        if (binding == null) {
            throw new AuthException(AuthErrorCode.OAUTH_ACCOUNT_NOT_BOUND,
                    "该第三方账号尚未绑定本地账号，请先登录后绑定");
        }
        AppUser user = authService.getById(binding.getUserId());
        return authService.issueLoginResponseForUser(user);
    }

    public Map<String, Object> listBindings(Integer userId) {
        if (!isActiveUser(userId)) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "本地账号不可用");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String provider : SUPPORTED_PROVIDERS) {
            UserOAuthBinding binding = findBindingByUserProvider(userId, provider);
            if (binding == null) {
                result.put(provider, Map.of("bound", false));
                continue;
            }
            Map<String, Object> bindingView = new LinkedHashMap<>();
            bindingView.put("bound", true);
            bindingView.put("provider", binding.getProvider());
            bindingView.put("displayName", safeDisplayName(binding.getDisplayName()));
            bindingView.put("email", maskEmail(binding.getProviderEmail()));
            bindingView.put("boundAt", binding.getCreateTime());
            result.put(provider, bindingView);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> unbind(Integer userId, String provider) {
        String normalizedProvider = normalizeProvider(provider);
        if (!isActiveUser(userId)) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "本地账号不可用");
        }
        UserOAuthBinding binding = findBindingByUserProvider(userId, normalizedProvider);
        if (binding == null) {
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_NOT_BOUND, "该登录方式尚未绑定");
        }
        AppUser user = authService.getById(userId);
        long bindingCount = bindingMapper.selectCount(new LambdaQueryWrapper<UserOAuthBinding>()
                .eq(UserOAuthBinding::getUserId, userId));
        boolean hasPasswordLogin = user != null && StringUtils.hasText(user.getPasswordHash());
        if (!hasPasswordLogin && bindingCount <= 1) {
            throw new AuthException(AuthErrorCode.OAUTH_LAST_LOGIN_METHOD,
                    "请先设置本地密码或绑定其他登录方式后再解绑");
        }
        bindingMapper.deleteById(binding.getBindingId());
        return Map.of("provider", normalizedProvider, "bound", false);
    }
    public String errorRedirect(Exception failure) {
        String code = failure instanceof AuthException auth ? String.valueOf(auth.getCode())
                : failure instanceof com.labex.auth.redis.AuthRedisUnavailableException
                ? String.valueOf(AuthErrorCode.REDIS_UNAVAILABLE) : "-1";
        String base = isBindingFailure(failure)
                ? bindingFrontendCallback() : properties.getOauthFrontendCallback();
        return base + (base.contains("?") ? "&" : "?") + "oauth_error=" + OAuthHttpClient.encode(code);
    }

    private boolean isBindingFailure(Exception failure) {
        if (!(failure instanceof AuthException auth)) {
            return false;
        }
        return auth.getCode() == AuthErrorCode.OAUTH_BIND_STATE_INVALID
                || auth.getCode() == AuthErrorCode.OAUTH_PROVIDER_ALREADY_BOUND
                || auth.getCode() == AuthErrorCode.OAUTH_IDENTITY_ALREADY_BOUND
                || auth.getCode() == AuthErrorCode.OAUTH_LAST_LOGIN_METHOD
                || auth.getCode() == AuthErrorCode.OAUTH_BIND_EXPIRED
                || auth.getCode() == AuthErrorCode.OAUTH_PROVIDER_NOT_BOUND
                || auth.getCode() == AuthErrorCode.INVALID_CREDENTIALS;
    }
    private void bindIdentity(Integer userId, OAuthIdentity identity) {
        if (!isActiveUser(userId)) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "本地账号不可用");
        }
        UserOAuthBinding existingIdentity = findBinding(identity.provider(), identity.subject());
        if (existingIdentity != null) {
            if (userId.equals(existingIdentity.getUserId())) {
                throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ALREADY_BOUND, "该登录方式已经绑定");
            }
            throw new AuthException(AuthErrorCode.OAUTH_IDENTITY_ALREADY_BOUND, "该第三方账号已绑定到其他账号");
        }
        if (findBindingByUserProvider(userId, identity.provider()) != null) {
            throw new AuthException(AuthErrorCode.OAUTH_PROVIDER_ALREADY_BOUND, "该登录方式已经绑定");
        }
        AppUser user = authService.getById(userId);
        saveBinding(user, identity);
    }

    private void saveBinding(AppUser user, OAuthIdentity identity) {
        UserOAuthBinding binding = new UserOAuthBinding();
        binding.setUserId(user.getUserId());
        binding.setProvider(identity.provider());
        binding.setSubject(identity.subject());
        binding.setProviderEmail(identity.email());
        binding.setDisplayName(identity.displayName());
        binding.setCreateTime(LocalDateTime.now());
        binding.setUpdateTime(LocalDateTime.now());
        bindingMapper.insert(binding);
    }

    private UserOAuthBinding findBinding(String provider, String subject) {
        return bindingMapper.selectOne(new LambdaQueryWrapper<UserOAuthBinding>()
                .eq(UserOAuthBinding::getProvider, provider)
                .eq(UserOAuthBinding::getSubject, subject)
                .last("LIMIT 1"));
    }

    private UserOAuthBinding findBindingByUserProvider(Integer userId, String provider) {
        return bindingMapper.selectOne(new LambdaQueryWrapper<UserOAuthBinding>()
                .eq(UserOAuthBinding::getUserId, userId)
                .eq(UserOAuthBinding::getProvider, provider)
                .last("LIMIT 1"));
    }

    private void saveState(String state, OAuthStatePayload payload) {
        try {
            redis.put(stateKey(state), objectMapper.writeValueAsString(payload),
                    Duration.ofSeconds(Math.max(1, properties.getOauthStateTtlSeconds())));
        } catch (JsonProcessingException failure) {
            throw new AuthException(AuthErrorCode.OAUTH_BIND_STATE_INVALID, "第三方授权暂时不可用，请重试");
        }
    }

    private OAuthStatePayload consumeState(String state, String provider) {
        String raw = redis.consume(stateKey(state));
        if (!StringUtils.hasText(raw)) {
            throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID, "第三方授权状态无效，请重试");
        }
        try {
            OAuthStatePayload payload = objectMapper.readValue(raw, OAuthStatePayload.class);
            if (!provider.equalsIgnoreCase(payload.provider())
                    || (!PURPOSE_LOGIN.equals(payload.purpose()) && !PURPOSE_BIND.equals(payload.purpose()))) {
                throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID, "第三方授权状态无效，请重试");
            }
            return payload;
        } catch (JsonProcessingException failure) {
            throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID, "第三方授权状态无效，请重试");
        }
    }

    private void saveCode(String key, OAuthIdentity identity) {
        try {
            redis.put(key, objectMapper.writeValueAsString(new OAuthCodePayload(identity.provider(), identity.subject(),
                    identity.email(), identity.displayName(), identity.avatarUrl())),
                    Duration.ofSeconds(Math.max(1, properties.getOauthCodeTtlSeconds())));
        } catch (JsonProcessingException failure) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "第三方授权暂时不可用，请重试");
        }
    }

    private OAuthCodePayload consumeCode(String key, int errorCode, String message) {
        String raw = redis.consume(key);
        if (!StringUtils.hasText(raw)) {
            throw new AuthException(errorCode, message);
        }
        try {
            return objectMapper.readValue(raw, OAuthCodePayload.class);
        } catch (JsonProcessingException failure) {
            throw new AuthException(errorCode, message);
        }
    }

    private OAuthIdentity toIdentity(OAuthCodePayload payload) {
        return new OAuthIdentity(payload.provider(), payload.subject(), payload.email(), payload.displayName(), payload.avatarUrl());
    }

    private String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase();
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw new AuthException(AuthErrorCode.OAUTH_NOT_CONFIGURED, "该第三方登录方式暂未配置");
        }
        registry.require(normalized);
        return normalized;
    }

    private boolean isActiveUser(Integer userId) {
        AppUser user = userId == null ? null : authService.getById(userId);
        return user != null && user.getStatus() != null && user.getStatus() == 1;
    }

    private String bindingFrontendCallback() {
        if (StringUtils.hasText(properties.getOauthBindingFrontendCallback())) {
            return properties.getOauthBindingFrontendCallback();
        }
        String loginCallback = properties.getOauthFrontendCallback();
        if (!StringUtils.hasText(loginCallback)) {
            throw new AuthException(AuthErrorCode.OAUTH_BIND_STATE_INVALID,
                    "第三方绑定回调地址未配置，请联系管理员");
        }
        int queryStart = loginCallback.indexOf('?');
        String callbackWithoutQuery = queryStart >= 0 ? loginCallback.substring(0, queryStart) : loginCallback;
        int lastSlash = callbackWithoutQuery.lastIndexOf('/');
        if (lastSlash < 0) {
            throw new AuthException(AuthErrorCode.OAUTH_BIND_STATE_INVALID,
                    "第三方绑定回调地址配置无效，请联系管理员");
        }
        return callbackWithoutQuery.substring(0, lastSlash + 1) + "projects";
    }

    private String callbackUri(String provider) {
        return properties.getOauthCallbackBaseUrl().replaceAll("/+$", "") + "/" + provider + "/callback";
    }

    private String redirectWithQuery(String base, String name, String value, String provider) {
        String separator = base.contains("?") ? "&" : "?";
        StringBuilder result = new StringBuilder(base).append(separator).append(name).append('=')
                .append(OAuthHttpClient.encode(value));
        if (provider != null && !provider.isBlank()) {
            result.append("&provider=").append(OAuthHttpClient.encode(provider));
        }
        return result.toString();
    }

    private String safeDisplayName(String displayName) {
        return StringUtils.hasText(displayName) ? displayName : "已绑定账号";
    }

    private String maskEmail(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            return null;
        }
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String visible = local.length() <= 1 ? local : local.substring(0, 1);
        return visible + "***" + email.substring(at);
    }

    public record AuthorizationRequest(String state, String url) {
    }

    private record OAuthStatePayload(String purpose, String provider, Integer userId) {
    }

    private String stateKey(String state) {
        return "labex:auth:oauth-state:" + safe(state);
    }

    private String codeKey(String code) {
        return "labex:auth:oauth-code:" + safe(code);
    }


    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9._~-]", "");
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}




