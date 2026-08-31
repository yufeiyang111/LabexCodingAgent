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

        // 1. 检查搜索内存缓存
        if (cache != null) {
            WebSearchResponse cached = cache.get(configuredProvider, request.query(), request.numResults());
            if (cached != null) {
                return cached;
            }
        }

        WebSearchResponse response = doSearch(configuredProvider, request, context);

        // 2. 写入搜索内存缓存
        if (cache != null && response != null) {
            cache.put(configuredProvider, request.query(), request.numResults(), response);
        }
        return response;
    }

    private WebSearchResponse doSearch(String configuredProvider, WebSearchRequest request, AgentContext context)
            throws WebSearchException {
        if ("tavily".equals(configuredProvider)) {
            return required(WebSearchProviderId.TAVILY).search(request, context);
        }
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
            throw new WebSearchException("Unsupported web-search provider configuration: " + configuredProvider, false);
        }

        // auto 模式智能链路：Tavily (优先) -> Exa -> Parallel -> Public Fallback
        WebSearchProvider tavily = providers.get(WebSearchProviderId.TAVILY);
        if (tavily != null && tavily.isEnabled()) {
            try {
                return tavily.search(request, context);
            } catch (WebSearchException e) {
                if (!e.isRecoverable()) {
                    throw e;
                }
            }
        }

        WebSearchProvider exa = providers.get(WebSearchProviderId.EXA);
        if (exa != null && exa.isEnabled()) {
            try {
                return exa.search(request, context);
            } catch (WebSearchException e) {
                if (!e.isRecoverable()) {
                    throw e;
                }
            }
        }

        WebSearchProvider parallel = providers.get(WebSearchProviderId.PARALLEL);
        if (parallel != null && parallel.isEnabled()) {
            try {
                return parallel.search(request, context);
            } catch (WebSearchException e) {
                if (!e.isRecoverable()) {
                    throw e;
                }
            }
        }

        WebSearchProvider fallback = providers.get(WebSearchProviderId.PUBLIC_FALLBACK);
        if (fallback != null && fallback.isEnabled()) {
            return fallback.search(request, context);
        }

        throw new WebSearchException("All available web search providers failed or are disabled", false);
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
