package com.labex.labexagent.websearch;

import com.labex.labexagent.runtime.AgentContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WebSearchProviderSelector {
    private final WebSearchProperties properties;
    private final Map<WebSearchProviderId, WebSearchProvider> providers;
    private final WebSearchCache cache;

    @Autowired
    public WebSearchProviderSelector(WebSearchProperties properties, List<WebSearchProvider> providers,
                                    @Autowired(required = false) WebSearchCache cache) {
        this.properties = properties;
        this.cache = cache;
        Map<WebSearchProviderId, WebSearchProvider> byId = new EnumMap<>(WebSearchProviderId.class);
        for (WebSearchProvider provider : providers) {
            byId.put(provider.id(), provider);
        }
        this.providers = Map.copyOf(byId);
    }

    public WebSearchProviderSelector(WebSearchProperties properties, List<WebSearchProvider> providers) {
        this(properties, providers, null);
    }

    /**
     * 返回当前配置路由是否存在可执行 provider；不发起网络请求，也不探测外部服务健康度。
     * schema 暴露只使用该本地配置事实，真实调用仍由 {@link #search(WebSearchRequest, AgentContext)} 执行。
     */
    public boolean isAvailable() {
        return switch (configuredProvider()) {
            case "tavily" -> isEnabled(WebSearchProviderId.TAVILY);
            case "exa" -> isEnabled(WebSearchProviderId.EXA);
            case "parallel" -> isEnabled(WebSearchProviderId.PARALLEL);
            case "public_fallback" -> isEnabled(WebSearchProviderId.PUBLIC_FALLBACK);
            case "auto" -> isEnabled(WebSearchProviderId.TAVILY)
                    || isEnabled(WebSearchProviderId.EXA)
                    || isEnabled(WebSearchProviderId.PARALLEL)
                    || isEnabled(WebSearchProviderId.PUBLIC_FALLBACK);
            default -> false;
        };
    }

    public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
        String configuredProvider = configuredProvider();
        if ("tavily".equals(configuredProvider)) {
            return searchWithCache(required(WebSearchProviderId.TAVILY), request, context);
        }
        if ("exa".equals(configuredProvider)) {
            return searchWithCache(required(WebSearchProviderId.EXA), request, context);
        }
        if ("parallel".equals(configuredProvider)) {
            return required(WebSearchProviderId.PARALLEL).search(request, context);
        }
        if ("public_fallback".equals(configuredProvider)) {
            return searchWithCache(required(WebSearchProviderId.PUBLIC_FALLBACK), request, context);
        }
        if (!"auto".equals(configuredProvider)) {
            throw new WebSearchException("Unsupported web-search provider configuration: " + configuredProvider, false);
        }

        // auto 模式智能链路：Tavily (优先) -> Exa -> Parallel -> Public Fallback
        WebSearchResponse response = tryProvider(WebSearchProviderId.TAVILY, request, context);
        if (response != null) {
            return response;
        }
        response = tryProvider(WebSearchProviderId.EXA, request, context);
        if (response != null) {
            return response;
        }
        response = tryProvider(WebSearchProviderId.PARALLEL, request, context);
        if (response != null) {
            return response;
        }
        WebSearchProvider fallback = providers.get(WebSearchProviderId.PUBLIC_FALLBACK);
        if (fallback != null && fallback.isEnabled()) {
            return searchWithCache(fallback, request, context);
        }
        throw new WebSearchException("All available web search providers failed or are disabled", false);
    }

    private WebSearchResponse tryProvider(WebSearchProviderId providerId, WebSearchRequest request,
                                          AgentContext context) throws WebSearchException {
        WebSearchProvider provider = providers.get(providerId);
        if (provider == null || !provider.isEnabled()) {
            return null;
        }
        try {
            return searchWithCache(provider, request, context);
        } catch (WebSearchException failure) {
            if (!failure.isRecoverable()) {
                throw failure;
            }
            return null;
        }
    }

    private WebSearchResponse searchWithCache(WebSearchProvider provider, WebSearchRequest request,
                                              AgentContext context) throws WebSearchException {
        WebSearchProviderId providerId = provider.id();
        if (cache != null && providerId != WebSearchProviderId.PARALLEL) {
            WebSearchResponse cached = cache.get(providerId, request, context);
            if (cached != null) {
                return cached;
            }
        }
        WebSearchResponse response = provider.search(request, context);
        if (cache != null && response != null && response.provider() == providerId
                && providerId != WebSearchProviderId.PARALLEL) {
            cache.put(providerId, request, context, response);
        }
        return response;
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
