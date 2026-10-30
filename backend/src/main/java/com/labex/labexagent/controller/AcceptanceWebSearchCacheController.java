package com.labex.labexagent.controller;

import com.labex.common.Result;
import com.labex.labexagent.websearch.WebSearchProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * acceptance 专用只读观测：仅暴露当前用户/项目下 Web Search 缓存键的聚合状态。
 *
 * <p>生产 profile 不注册；响应不包含完整键、缓存值或 query 明文。</p>
 */
@RestController
@Profile("acceptance & !prod")
@RequestMapping("/student/projects/{projectId}/agent/acceptance")
public class AcceptanceWebSearchCacheController {
    private static final String QUERY_PROBE = "LabexAgent Redis shared cache acceptance";

    private final StringRedisTemplate redis;
    private final WebSearchProperties properties;

    public AcceptanceWebSearchCacheController(StringRedisTemplate redis, WebSearchProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @GetMapping("/web-search-cache")
    public Result<Map<String, Object>> observe(@PathVariable Integer projectId, Authentication auth) {
        Integer studentId = Integer.valueOf(auth.getName());
        String prefix = normalizePrefix(properties.getCacheRedisKeyPrefix())
                + ":v1:" + studentId + ":" + projectId + ":";
        Set<String> keys = redis.keys(prefix + "*");
        List<String> keyList = keys == null ? List.of() : new ArrayList<>(keys);
        boolean queryLeaked = keyList.stream().anyMatch(key -> key.contains(QUERY_PROBE));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("redisEnabled", properties.isCacheEnabled() && properties.isCacheRedisEnabled());
        evidence.put("matchingKeys", keyList.size());
        evidence.put("queryLeaked", queryLeaked);
        evidence.put("providers", keyList.stream()
                .map(this::providerFromKey)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList());
        return Result.success(evidence);
    }

    private String providerFromKey(String key) {
        String[] segments = key.split(":");
        return segments.length >= 2 ? segments[segments.length - 2] : "";
    }

    private String normalizePrefix(String value) {
        return value == null || value.isBlank() ? "labex:web-search" : value.trim();
    }
}