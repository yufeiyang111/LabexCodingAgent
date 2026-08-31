package com.labex.labexagent.websearch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 线程安全的 Web Search 轻量 LRU 内存缓存，防止短时间内重复查询相同的网络请求。
 */
@Component
public class WebSearchCache {
    private final WebSearchProperties properties;
    private final Map<CacheKey, CacheEntry> cache;

    public WebSearchCache(WebSearchProperties properties) {
        this.properties = properties;
        final int maxEntries = Math.max(10, properties.getCacheMaxEntries());
        this.cache = new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized WebSearchResponse get(String provider, String query, int numResults) {
        if (!properties.isCacheEnabled()) {
            return null;
        }
        CacheKey key = new CacheKey(normalize(provider), normalize(query), numResults);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long ttlMillis = properties.getCacheTtlSeconds() * 1000L;
        if (now - entry.createdAt > ttlMillis) {
            cache.remove(key);
            return null;
        }
        return entry.response;
    }

    public synchronized void put(String provider, String query, int numResults, WebSearchResponse response) {
        if (!properties.isCacheEnabled() || response == null) {
            return;
        }
        CacheKey key = new CacheKey(normalize(provider), normalize(query), numResults);
        cache.put(key, new CacheEntry(response, System.currentTimeMillis()));
    }

    public synchronized void clear() {
        cache.clear();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private record CacheKey(String provider, String query, int numResults) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return numResults == cacheKey.numResults
                    && Objects.equals(provider, cacheKey.provider)
                    && Objects.equals(query, cacheKey.query);
        }

        @Override
        public int hashCode() {
            return Objects.hash(provider, query, numResults);
        }
    }

    private record CacheEntry(WebSearchResponse response, long createdAt) {}
}
