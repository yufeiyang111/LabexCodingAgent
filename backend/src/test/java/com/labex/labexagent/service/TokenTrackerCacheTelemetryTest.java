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
import com.labex.labexagent.llm.CacheTelemetry;
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

        Map<String, Object> stats = tracker.getStudentStats(1);

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

        Map<String, Object> stats = tracker.getStudentStats(1);

        assertEquals("hit", stats.get("cacheStatus"));
        assertEquals(2, stats.get("cacheTelemetryCallCount"));
        assertEquals(20.0, stats.get("cacheHitRate"));
    }


    /**
     * 逐次 CacheTelemetry.hitRate 在 provider 明确上报 hit/miss 时以 hit+miss 作分母；
     * 聚合必须同一口径，否则面板总数与单次数字会互相打架。
     */
    @Test
    @SuppressWarnings("unchecked")
    void aggregateUsesTheSameMissAwareDenominatorAsPerCallTelemetry() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        AgentTokenUsage missAware = usage(60, 40, CacheTelemetryStatus.HIT);
        missAware.setCacheHitTokens(40);
        missAware.setCacheMissTokens(60);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(missAware));
        TokenTracker tracker = new TokenTracker(mapper);

        Map<String, Object> stats = tracker.getStudentStats(1);

        // 40 / (40 + 60) = 40%，与逐次口径一致（而不是 40 / 60 = 66.67%）。
        assertEquals(40.0, stats.get("cacheHitRate"));
        assertEquals(40.0, CacheTelemetry.hitRate(true, Map.of(
                "prompt_tokens", 60, "cached_tokens", 40, "cache_miss_tokens", 60,
                "cache_usage_reported", true)));
    }

    /**
     * 缓存 token 只是全量 prompt 的一个子集时（Anthropic 口径：hit+write = 可缓存部分），
     * 分母必须仍取全量 prompt，不能被 hit+miss 缩掉——取 max 保证既有数字不漂移。
     */
    @Test
    @SuppressWarnings("unchecked")
    void aggregateKeepsFullPromptDenominatorWhenCacheTokensAreOnlyASubset() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        AgentTokenUsage anthropicShaped = usage(1400, 300, CacheTelemetryStatus.HIT);
        anthropicShaped.setCacheHitTokens(300);
        anthropicShaped.setCacheMissTokens(400);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(anthropicShaped));
        TokenTracker tracker = new TokenTracker(mapper);

        Map<String, Object> stats = tracker.getStudentStats(1);

        // 300 / 1400 = 21.43%，而不是 300 / 700 = 42.86%。
        assertEquals(21.43, stats.get("cacheHitRate"));
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

    @Test
    void returnsPersistedConversationSummariesInServerOrder() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        when(mapper.selectConversationSummaries(7, 12)).thenReturn(List.of(
                row("conv-recent", 900, 100, 1000, 7, "2026-09-11T10:00"),
                row("conv-earlier", 40, 10, 50, 2, "2026-09-09T08:00")));
        TokenTracker tracker = new TokenTracker(mapper);

        List<Map<String, Object>> summaries = tracker.getConversationSummaries(7, 12);

        assertEquals(2, summaries.size());
        assertEquals("conv-recent", summaries.get(0).get("conversationId"));
        assertEquals(1000, summaries.get(0).get("totalTokens"));
        assertEquals(900, summaries.get(0).get("promptTokens"));
        assertEquals(100, summaries.get(0).get("completionTokens"));
        assertEquals(7, summaries.get(0).get("callCount"));
        assertEquals("2026-09-11T10:00", summaries.get(0).get("lastUsedAt"));
        // 标题由控制层补齐，聚合层不得越界查会话表。
        assertEquals(false, summaries.get(0).containsKey("title"));
    }

    @Test
    void toleratesDriverKeyCaseAndDecimalAggregates() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        Map<String, Object> upperCased = new java.util.LinkedHashMap<>();
        upperCased.put("CONVERSATIONID", "conv-a");
        upperCased.put("TOTALTOKENS", new java.math.BigDecimal("1500.0"));
        upperCased.put("CALLCOUNT", 3L);
        when(mapper.selectConversationSummaries(7, 12)).thenReturn(List.of(upperCased));
        TokenTracker tracker = new TokenTracker(mapper);

        List<Map<String, Object>> summaries = tracker.getConversationSummaries(7, 12);

        assertEquals(1, summaries.size());
        assertEquals("conv-a", summaries.get(0).get("conversationId"));
        assertEquals(1500, summaries.get(0).get("totalTokens"));
        assertEquals(3, summaries.get(0).get("callCount"));
        // 缺列按 0 处理，绝不产生 null/NaN 让前端崩。
        assertEquals(0, summaries.get(0).get("promptTokens"));
    }

    @Test
    void skipsBlankConversationIds() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        when(mapper.selectConversationSummaries(7, 12)).thenReturn(List.of(row(" ", 1, 1, 2, 1, null)));
        TokenTracker tracker = new TokenTracker(mapper);

        assertEquals(0, tracker.getConversationSummaries(7, 12).size());
    }

    @Test
    void refusesToAggregateWithoutStudentOrProjectScope() {
        AgentTokenUsageMapper mapper = mock(AgentTokenUsageMapper.class);
        TokenTracker tracker = new TokenTracker(mapper);

        assertEquals(0, tracker.getConversationSummaries(null, 12).size());
        assertEquals(0, tracker.getConversationSummaries(7, null).size());
        assertEquals(0, tracker.getConversationSummaries(null, null).size());
        // 作用域缺失时不得发起查询，避免跨用户聚合。
        verify(mapper, never()).selectConversationSummaries(any(), any());
    }

    private static Map<String, Object> row(String conversationId, int prompt, int completion,
                                           int total, int calls, String lastUsedAt) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("conversationId", conversationId);
        row.put("promptTokens", prompt);
        row.put("completionTokens", completion);
        row.put("totalTokens", total);
        row.put("callCount", calls);
        row.put("lastUsedAt", lastUsedAt);
        return row;
    }

    private AgentTokenUsage usage(int promptTokens, int cachedTokens, CacheTelemetryStatus status) {
        AgentTokenUsage usage = new AgentTokenUsage("conversation", "session", 1, 2,
                "provider", "model", promptTokens, 10, promptTokens + 10, 1, null);
        usage.setCachedTokens(cachedTokens);
        usage.setCacheStatus(status.value());
        return usage;
    }
}
