package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTokenUsage;
import com.labex.labexagent.llm.CacheTelemetryStatus;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.mapper.AgentTokenUsageMapper;
import java.time.LocalDateTime;
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

    @Test
    void exposesDailyCacheHitRateForTheStudentSummary() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        AgentTokenUsage hit = usage(100, 40, CacheTelemetryStatus.HIT);
        AgentTokenUsage miss = usage(100, 0, CacheTelemetryStatus.MISS);
        hit.setCreateTime(LocalDateTime.of(2026, 8, 5, 10, 0));
        miss.setCreateTime(LocalDateTime.of(2026, 8, 5, 11, 0));
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(hit, miss));
        TokenTracker tracker = new TokenTracker(mapper);

        Map<String, Object> stats = tracker.getStudentStats(1);

        Map<?, ?> cacheByDay = (Map<?, ?>) stats.get("cacheByDay");
        Map<?, ?> daily = (Map<?, ?>) cacheByDay.get("2026-08-05");
        assertEquals(20.0, daily.get("cacheHitRate"));
        assertEquals("hit", daily.get("cacheStatus"));
        assertEquals(2, daily.get("cacheTelemetryCallCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesCacheTelemetryByModelForPromptCacheFiltering() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        AgentTokenUsage gptHit = usage(100, 40, CacheTelemetryStatus.HIT);
        gptHit.setModel("gpt-5");
        gptHit.setCacheWriteTokens(60);
        AgentTokenUsage gptMiss = usage(100, 0, CacheTelemetryStatus.MISS);
        gptMiss.setModel("gpt-5");
        AgentTokenUsage qwenUnreported = usage(80, 0, CacheTelemetryStatus.NOT_REPORTED);
        qwenUnreported.setModel("qwen-max");
        AgentTokenUsage unnamed = usage(50, 0, CacheTelemetryStatus.HIT);
        unnamed.setModel(" ");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(gptHit, gptMiss, qwenUnreported, unnamed));
        TokenTracker tracker = new TokenTracker(mapper);

        Map<String, Object> stats = tracker.getStudentStats(1);
        Map<String, Map<String, Object>> cacheByModel = (Map<String, Map<String, Object>>) stats.get("cacheByModel");

        assertEquals(List.of("gpt-5", "qwen-max"), List.copyOf(cacheByModel.keySet()));
        Map<String, Object> gptStats = cacheByModel.get("gpt-5");
        assertEquals("hit", gptStats.get("cacheStatus"));
        assertEquals(2, gptStats.get("cacheTelemetryCallCount"));
        assertEquals(20.0, gptStats.get("cacheHitRate"));
        assertEquals(40, gptStats.get("totalCachedTokens"));
        assertEquals(60, gptStats.get("totalCacheWriteTokens"));
        assertEquals("not_reported", cacheByModel.get("qwen-max").get("cacheStatus"));
        assertEquals(0, cacheByModel.get("qwen-max").get("cacheTelemetryCallCount"));
        assertNull(cacheByModel.get("qwen-max").get("cacheHitRate"));
    }

    @Test
    void rejectsFencedUsageWhenTheExecutionLeaseIsStale() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        doThrow(new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_EPOCH))
                .when(leases).requireActiveFenceForWrite(any(), any());
        TokenTracker tracker = new TokenTracker(mapper, null, leases);

        assertThrows(AgentRunExecutionLeaseService.StaleExecutionFenceException.class,
                () -> tracker.recordWithFence(new ExecutionFence(71L, "instance-a", 2L),
                        "conversation", "session", 7, 12, "provider", "model",
                        100, 20, 120, 40, 0, 40, 60,
                        CacheTelemetryStatus.HIT, 1, null));

        verify(mapper, never()).insert(any());
    }

    @Test
    void storesTaskAndEpochIdentityOnFencedUsage() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        TokenTracker tracker = new TokenTracker(mapper, null, leases);

        tracker.recordWithFence(new ExecutionFence(71L, "instance-a", 4L),
                "conversation", "session", 7, 12, "provider", "model",
                100, 20, 120, 40, 0, 40, 60,
                CacheTelemetryStatus.HIT, 1, null);

        ArgumentCaptor<AgentTokenUsage> captor = ArgumentCaptor.forClass(AgentTokenUsage.class);
        verify(mapper).insert(captor.capture());
        assertEquals(71L, captor.getValue().getTaskId());
        assertEquals(4L, captor.getValue().getExecutionEpoch());
    }

    private AgentTokenUsage usage(int promptTokens, int cachedTokens, CacheTelemetryStatus status) {
        AgentTokenUsage usage = new AgentTokenUsage("conversation", "session", 1, 2,
                "provider", "model", promptTokens, 10, promptTokens + 10, 1, null);
        usage.setCachedTokens(cachedTokens);
        usage.setCacheStatus(status.value());
        return usage;
    }
}
