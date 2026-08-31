package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheTelemetryTest {
    @Test
    void distinguishesDisabledAndUnreportedTelemetry() {
        assertEquals(CacheTelemetryStatus.DISABLED, CacheTelemetry.status(false, null));
        assertEquals(CacheTelemetryStatus.NOT_REPORTED, CacheTelemetry.status(true, Map.of()));
        assertNull(CacheTelemetry.hitRate(true, Map.of("prompt_tokens", 100)));
    }

    @Test
    void distinguishesHitMissAndWriteOnlyTelemetry() {
        assertEquals(CacheTelemetryStatus.HIT, CacheTelemetry.status(true,
                Map.of("prompt_tokens", 100, "cached_tokens", 40, "cache_usage_reported", true)));
        assertEquals(CacheTelemetryStatus.MISS, CacheTelemetry.status(true,
                Map.of("prompt_tokens", 100, "cached_tokens", 0, "cache_usage_reported", true)));
        assertEquals(CacheTelemetryStatus.WRITE_ONLY, CacheTelemetry.status(true,
                Map.of("prompt_tokens", 100, "cache_write_tokens", 100, "cache_usage_reported", true)));
        assertEquals(CacheTelemetryStatus.HIT, CacheTelemetry.status(false,
                Map.of("prompt_tokens", 100, "cached_tokens", 40, "cache_usage_reported", true)));
        assertEquals(40.0, CacheTelemetry.hitRate(true,
                Map.of("prompt_tokens", 100, "cached_tokens", 40, "cache_usage_reported", true)));
    }

    @Test
    void capsHitRateAtOneHundredAndClampsNegativeCachedTokens() {
        assertEquals(100.0, CacheTelemetry.hitRate(true,
                Map.of("prompt_tokens", 100, "cached_tokens", 200, "cache_usage_reported", true)));
        assertEquals(0.0, CacheTelemetry.hitRate(true,
                Map.of("prompt_tokens", 100, "cached_tokens", -10, "cache_usage_reported", true)));
        assertNull(CacheTelemetry.hitRate(true,
                Map.of("prompt_tokens", -100, "cached_tokens", 40, "cache_usage_reported", true)));
    }

    @Test
    void derivesMutuallyConsistentHitAndMissFieldsFromCachedOnlyUsage() {
        Map<String, Object> normalized = CacheTelemetry.normalizeUsage(Map.of(
                "prompt_tokens", 100,
                "cached_tokens", 40,
                "cache_hit_tokens", 0,
                "cache_miss_tokens", 0,
                "cache_usage_reported", true));

        assertEquals(40, normalized.get("cache_hit_tokens"));
        assertEquals(60, normalized.get("cache_miss_tokens"));
        assertEquals(40.0, CacheTelemetry.hitRate(true, normalized));
    }
}
