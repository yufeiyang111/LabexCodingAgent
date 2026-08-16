package com.labex.labexagent.websearch;

import com.labex.labexagent.runtime.AgentContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class WebSearchProviderSelector {
    private final WebSearchProperties properties;
    private final Map<WebSearchProviderId, WebSearchProvider> providers;

    public WebSearchProviderSelector(WebSearchProperties properties, List<WebSearchProvider> providers) {
        this.properties = properties;
        Map<WebSearchProviderId, WebSearchProvider> byId = new EnumMap<>(WebSearchProviderId.class);
        for (WebSearchProvider provider : providers) {
            byId.put(provider.id(), provider);
        }
        this.providers = Map.copyOf(byId);
    }

    /**
     * 返回当前配置路由是否存在可执行 provider；不发起网络请求，也不探测外部服务健康度。
     * schema 暴露只使用该本地配置事实，真实调用仍由 {@link #search(WebSearchRequest, AgentContext)} 执行。
     */
    public boolean isAvailable() {
        return switch (configuredProvider()) {
            case "exa" -> isEnabled(WebSearchProviderId.EXA);
            case "parallel" -> isEnabled(WebSearchProviderId.PARALLEL);
            case "public_fallback" -> isEnabled(WebSearchProviderId.PUBLIC_FALLBACK);
            // auto 的实际调用先走 Exa；fallback 只处理 Exa 的可恢复运行时失败。
            case "auto" -> isEnabled(WebSearchProviderId.EXA);
            default -> false;
        };
    }

    public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
        String configuredProvider = configuredProvider();
        if ("exa".equals(configuredProvider)) {
            return required(WebSearchProviderId.EXA).search(request, context);
        }
        if ("parallel".equals(configuredProvider)) {
            return required(WebSearchProviderId.PARALLEL).search(request, context);
        }
        if ("public_fallback".equals(configuredProvider)) {
            return required(WebSearchProviderId.PUBLIC_FALLBACK).search(request, context);
        }
        if (!"auto".equals(configuredProvider)) {
            throw new WebSearchException("Unsupported web-search provider configuration", false);
        }

        WebSearchProvider exa = required(WebSearchProviderId.EXA);
        if (!exa.isEnabled()) {
            throw new WebSearchException("Exa web search is disabled", false);
        }
        try {
            return exa.search(request, context);
        } catch (WebSearchException exaFailure) {
            if (!exaFailure.isRecoverable()) {
                throw exaFailure;
            }
            WebSearchProvider fallback = providers.get(WebSearchProviderId.PUBLIC_FALLBACK);
            if (fallback == null || !fallback.isEnabled()) {
                throw exaFailure;
            }
            return fallback.search(request, context);
        }
    }

    private boolean isEnabled(WebSearchProviderId providerId) {
        WebSearchProvider provider = providers.get(providerId);
        return provider != null && provider.isEnabled();
    }

    private WebSearchProvider required(WebSearchProviderId providerId) throws WebSearchException {
        WebSearchProvider provider = providers.get(providerId);
        if (provider == null || !provider.isEnabled()) {
            throw new WebSearchException(providerId.name().toLowerCase(Locale.ROOT)
                    + " web search is disabled or unavailable", false);
        }
        return provider;
    }

    private String configuredProvider() {
        String provider = properties.getProvider();
        return provider == null || provider.isBlank() ? "auto" : provider.trim().toLowerCase(Locale.ROOT);
    }
}
