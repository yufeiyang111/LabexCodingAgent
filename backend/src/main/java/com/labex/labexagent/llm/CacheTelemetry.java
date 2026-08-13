package com.labex.labexagent.llm;

import java.util.Map;

public final class CacheTelemetry {
    private CacheTelemetry() {
    }

    public static CacheTelemetryStatus status(boolean cacheEnabled, Map<String, Object> usage) {
        boolean telemetryReported = usage != null && Boolean.TRUE.equals(usage.get("cache_usage_reported"));
        if (telemetryReported) {
            if (intValue(usage, "cached_tokens") > 0) return CacheTelemetryStatus.HIT;
            if (intValue(usage, "cache_write_tokens") > 0) return CacheTelemetryStatus.WRITE_ONLY;
            return CacheTelemetryStatus.MISS;
        }
        return cacheEnabled ? CacheTelemetryStatus.NOT_REPORTED : CacheTelemetryStatus.DISABLED;
    }

    public static Double hitRate(boolean cacheEnabled, Map<String, Object> usage) {
        CacheTelemetryStatus status = status(cacheEnabled, usage);
        if (status == CacheTelemetryStatus.DISABLED || status == CacheTelemetryStatus.NOT_REPORTED) return null;
        int promptTokens = intValue(usage, "prompt_tokens");
        int cachedTokens = intValue(usage, "cached_tokens");
        int missTokens = intValue(usage, "cache_miss_tokens");
        boolean missAware = usage.containsKey("cache_miss_tokens") || usage.containsKey("cache_hit_tokens");
        int denominator = missAware ? cachedTokens + missTokens : promptTokens;
        if (denominator <= 0) return null;
        return Math.round(cachedTokens * 10000.0 / denominator) / 100.0;
    }

    private static int intValue(Map<String, Object> usage, String key) {
        if (usage == null) return 0;
        Object value = usage.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
