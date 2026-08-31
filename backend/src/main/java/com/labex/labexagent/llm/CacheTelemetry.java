package com.labex.labexagent.llm;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public final class CacheTelemetry {
    private CacheTelemetry() {
    }

    public static CacheTelemetryStatus status(boolean cacheEnabled, Map<String, Object> usage) {
        Map<String, Object> normalized = normalizeUsage(usage);
        boolean telemetryReported = Boolean.TRUE.equals(normalized.get("cache_usage_reported"));
        if (telemetryReported) {
            if (intValue(normalized, "cached_tokens") > 0) return CacheTelemetryStatus.HIT;
            if (intValue(normalized, "cache_write_tokens") > 0) return CacheTelemetryStatus.WRITE_ONLY;
            return CacheTelemetryStatus.MISS;
        }
        return cacheEnabled ? CacheTelemetryStatus.NOT_REPORTED : CacheTelemetryStatus.DISABLED;
    }

    public static Double hitRate(boolean cacheEnabled, Map<String, Object> usage) {
        Map<String, Object> normalized = normalizeUsage(usage);
        CacheTelemetryStatus status = status(cacheEnabled, normalized);
        if (status == CacheTelemetryStatus.DISABLED || status == CacheTelemetryStatus.NOT_REPORTED) return null;
        int promptTokens = intValue(normalized, "prompt_tokens");
        int cachedTokens = intValue(normalized, "cached_tokens");
        int missTokens = intValue(normalized, "cache_miss_tokens");
        boolean missAware = missTokens > 0;
        int denominator = missAware ? cachedTokens + missTokens : promptTokens;
        if (denominator <= 0) return null;
        // 对齐 opencode getUsage safe()：cached 已由 intValue 归零；比率钳制在 [0, 100]，
        // 脏数据（cached > prompt）不得产生 >100% 或负数命中率。
        return Math.min(100.0, Math.round(cachedTokens * 10000.0 / denominator) / 100.0);
    }

    /**
     * 统一不同 OpenAI-compatible 网关的缓存字段口径。
     *
     * <p>部分网关只返回 {@code cached_tokens}，但请求链路会预先放入值为零的 hit/miss
     * 字段。零值不能覆盖已知的 cached 部分，否则 miss-aware 分母会变成 cached 本身并
     * 把命中率错误地显示为 100%。</p>
     */
    public static Map<String, Object> normalizeUsage(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return usage == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(usage));
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(usage);
        if (!Boolean.TRUE.equals(normalized.get("cache_usage_reported"))) {
            return Collections.unmodifiableMap(normalized);
        }

        int prompt = intValue(normalized, "prompt_tokens");
        int cached = intValue(normalized, "cached_tokens");
        int hit = intValue(normalized, "cache_hit_tokens");
        int miss = intValue(normalized, "cache_miss_tokens");
        int cacheWrite = intValue(normalized, "cache_write_tokens");

        if (hit <= 0 && cached > 0) {
            normalized.put("cache_hit_tokens", cached);
        }
        if (miss <= 0 && (prompt > 0 || cached > 0 || cacheWrite > 0)) {
            normalized.put("cache_miss_tokens", Math.max(0, prompt - cached));
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static int intValue(Map<String, Object> usage, String key) {
        if (usage == null) return 0;
        Object value = usage.get(key);
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
