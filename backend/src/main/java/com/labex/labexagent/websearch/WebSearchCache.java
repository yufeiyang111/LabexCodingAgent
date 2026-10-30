package com.labex.labexagent.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Web Search 的 L1 进程内 LRU + L2 Redis 共享缓存。
 *
 * <p>缓存只保存可丢弃的成功搜索结果；用户/项目作用域不完整、项目归属不匹配或
 * Parallel provider 均直接绕过。Redis 与反序列化故障一律 fail-open。</p>
 */
@Component
public class WebSearchCache {
    private static final Logger log = LoggerFactory.getLogger(WebSearchCache.class);
    private static final String SCHEMA_VERSION = "v1";

    private final WebSearchProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Map<CacheKey, CacheEntry> cache;

    @Autowired
    public WebSearchCache(WebSearchProperties properties, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
        final int maxEntries = Math.max(10, properties.getCacheMaxEntries());
        this.cache = new LinkedHashMap<>(maxEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    WebSearchCache(WebSearchProperties properties) {
        this(properties, null, new ObjectMapper());
    }

    public WebSearchResponse get(WebSearchProviderId providerId, WebSearchRequest request,
                                 AgentContext context) {
        CacheKey key = cacheKey(providerId, request, context);
        if (key == null) {
            return null;
        }
        WebSearchResponse l1 = getL1(key);
        if (l1 != null) {
            return l1;
        }
        if (!redisEnabled()) {
            return null;
        }
        WebSearchResponse l2 = getL2(key);
        if (l2 != null) {
            putL1(key, l2);
        }
        return l2;
    }

    public void put(WebSearchProviderId providerId, WebSearchRequest request, AgentContext context,
                    WebSearchResponse response) {
        if (response == null || providerId == null || response.provider() != providerId) {
            return;
        }
        CacheKey key = cacheKey(providerId, request, context);
        if (key == null) {
            return;
        }
        String serialized = serialize(response);
        if (serialized == null) {
            return;
        }
        if (!withinValueLimit(serialized)) {
            removeL1(key);
            return;
        }
        putL1(key, response);
        putL2(key, serialized);
    }

    public synchronized void clear() {
        cache.clear();
    }

    private synchronized WebSearchResponse getL1(CacheKey key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        long ttlMillis = Math.max(0, properties.getCacheTtlSeconds()) * 1000L;
        if (System.currentTimeMillis() - entry.createdAt() > ttlMillis) {
            cache.remove(key);
            return null;
        }
        return entry.response();
    }

    private synchronized void putL1(CacheKey key, WebSearchResponse response) {
        if (properties.isCacheEnabled()) {
            cache.put(key, new CacheEntry(response, System.currentTimeMillis()));
        }
    }

    private synchronized void removeL1(CacheKey key) {
        cache.remove(key);
    }

    private WebSearchResponse getL2(CacheKey key) {
        try {
            String value = redis.opsForValue().get(key.redisKey());
            if (value == null) {
                return null;
            }
            if (!withinValueLimit(value)) {
                deleteQuietly(key);
                return null;
            }
            WebSearchResponse response = decode(value, key);
            if (response == null) {
                deleteQuietly(key);
                return null;
            }
            if (response.provider() != key.providerId()) {
                deleteQuietly(key);
                return null;
            }
            return response;
        } catch (Exception failure) {
            logFailOpen("read", key.providerId(), failure);
            return null;
        }
    }

    private WebSearchResponse decode(String value, CacheKey key) {
        try {
            return objectMapper.readValue(value, WebSearchResponse.class);
        } catch (Exception parseFailure) {
            logFailOpen("decode", key.providerId(), parseFailure);
            return null;
        }
    }

    private void putL2(CacheKey key, String serialized) {
        if (!redisEnabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key.redisKey(), serialized, ttlSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (RuntimeException failure) {
            logFailOpen("write", key.providerId(), failure);
        }
    }

    private void deleteQuietly(CacheKey key) {
        try {
            redis.delete(key.redisKey());
        } catch (RuntimeException failure) {
            logFailOpen("delete", key.providerId(), failure);
        }
    }

    private boolean redisEnabled() {
        return properties.isCacheEnabled() && properties.isCacheRedisEnabled() && redis != null;
    }

    private String serialize(WebSearchResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception failure) {
            logFailOpen("serialize", response.provider(), failure);
            return null;
        }
    }

    private long ttlSeconds() {
        return Math.max(0, properties.getCacheTtlSeconds());
    }

    private boolean withinValueLimit(String serialized) {
        return serialized.getBytes(StandardCharsets.UTF_8).length <= Math.max(0, properties.getCacheMaxValueBytes());
    }

    private void logFailOpen(String operation, WebSearchProviderId provider, Exception failure) {
        log.warn("web search cache {} skipped provider={} error={}", operation,
                provider == null ? "unknown" : provider.name().toLowerCase(Locale.ROOT), failure.getClass().getSimpleName());
    }

    private CacheKey cacheKey(WebSearchProviderId providerId, WebSearchRequest request, AgentContext context) {
        if (!properties.isCacheEnabled() || providerId == null || request == null
                || providerId == WebSearchProviderId.PARALLEL) {
            return null;
        }
        Integer studentId = context == null ? null : context.getStudentId();
        StudentProject project = context == null ? null : context.getProject();
        if (studentId == null || project == null || project.getProjectId() == null
                || !studentId.equals(project.getStudentId())) {
            return null;
        }
        String requestDigest = requestDigest(request);
        if (requestDigest.isBlank()) {
            return null;
        }
        String prefix = normalizePrefix(properties.getCacheRedisKeyPrefix());
        String redisKey = prefix + ":" + SCHEMA_VERSION + ":" + studentId + ":" + project.getProjectId()
                + ":" + providerId.name().toLowerCase(Locale.ROOT) + ":" + requestDigest;
        return new CacheKey(providerId, redisKey);
    }

    private String requestDigest(WebSearchRequest request) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("query", request.query());
            canonical.put("numResults", request.numResults());
            canonical.put("livecrawl", request.livecrawl());
            canonical.put("type", request.type());
            canonical.put("contextMaxCharacters", request.contextMaxCharacters());
            byte[] json = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception failure) {
            logFailOpen("digest", null, failure);
            return "";
        }
    }

    private String normalizePrefix(String prefix) {
        return prefix == null || prefix.isBlank() ? "labex:web-search" : prefix.trim();
    }

    private record CacheKey(WebSearchProviderId providerId, String redisKey) {
    }

    private record CacheEntry(WebSearchResponse response, long createdAt) {
    }
}