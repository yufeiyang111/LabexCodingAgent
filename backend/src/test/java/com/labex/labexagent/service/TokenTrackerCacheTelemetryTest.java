package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTokenUsage;
import com.labex.labexagent.llm.CacheTelemetryStatus;
import com.labex.mapper.AgentTokenUsageMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TokenTrackerCacheTelemetryTest {
    @Test
    void persistsExplicitCacheTelemetryStatus() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        TokenTracker tracker = new TokenTracker(mapper);

        tracker.record("conversation", "session", 1, 2, "provider", "model",
                100, 20, 120, 40, 0, CacheTelemetryStatus.HIT, 3, null);

        ArgumentCaptor<AgentTokenUsage> captor = ArgumentCaptor.forClass(AgentTokenUsage.class);
        verify(mapper).insert(captor.capture());
        assertEquals("hit", captor.getValue().getCacheStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotReportZeroPercentWhenProviderTelemetryIsUnavailable() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                usage(100, 0, CacheTelemetryStatus.DISABLED),
                usage(200, 0, CacheTelemetryStatus.NOT_REPORTED)));
        TokenTracker tracker = new TokenTracker(mapper);

        Map<String, Object> stats = tracker.getConversationStats("conversation");

        assertEquals("not_reported", stats.get("cacheStatus"));
        assertEquals(0, stats.get("cacheTelemetryCallCount"));
        assertNull(stats.get("cacheHitRate"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void computesHitRateOnlyFromProviderReportedCalls() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                usage(1000, 0, CacheTelemetryStatus.NOT_REPORTED),
                usage(100, 40, CacheTelemetryStatus.HIT),
                usage(100, 0, CacheTelemetryStatus.MISS)));
        TokenTracker tracker = new TokenTracker(mapper);

        Map<String, Object> stats = tracker.getConversationStats("conversation");

        assertEquals("hit", stats.get("cacheStatus"));
        assertEquals(2, stats.get("cacheTelemetryCallCount"));
        assertEquals(20.0, stats.get("cacheHitRate"));
    }


    @Test
    void exposesCacheTotalsInStudentSummary() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        AgentTokenUsage hit = usage(100, 40, CacheTelemetryStatus.HIT);
        hit.setCompletionTokens(20);
        hit.setTotalTokens(120);
        hit.setCacheWriteTokens(60);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(hit));
        TokenTracker tracker = new TokenTracker(mapper);

        Map<String, Object> stats = tracker.getStudentStats(1);

        assertEquals(100, stats.get("totalPromptTokens"));
        assertEquals(20, stats.get("totalCompletionTokens"));
        assertEquals(40, stats.get("totalCachedTokens"));
        assertEquals(60, stats.get("totalCacheWriteTokens"));
        assertEquals("hit", stats.get("cacheStatus"));
        assertEquals(40.0, stats.get("cacheHitRate"));
    }

    private AgentTokenUsage usage(int promptTokens, int cachedTokens, CacheTelemetryStatus status) {
        AgentTokenUsage usage = new AgentTokenUsage("conversation", "session", 1, 2,
                "provider", "model", promptTokens, 10, promptTokens + 10, 1, null);
        usage.setCachedTokens(cachedTokens);
        usage.setCacheStatus(status.value());
        return usage;
    }
}