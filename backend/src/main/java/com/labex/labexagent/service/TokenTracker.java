package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTokenUsage;
import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.llm.CacheTelemetry;
import com.labex.labexagent.llm.CacheTelemetryStatus;
import com.labex.labexagent.projectconfig.AgentRunConfigSnapshotService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.mapper.AgentTokenUsageMapper;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TokenTracker {

    private final AgentTokenUsageMapper mapper;
    private final AgentRunConfigSnapshotService prefixEvidenceService;
    private final AgentRunExecutionLeaseService executionLeaseService;
    private final AgentRunLifecycleService runLifecycleService;

    @Autowired
    public TokenTracker(AgentTokenUsageMapper mapper, AgentRunConfigSnapshotService prefixEvidenceService,
                        AgentRunExecutionLeaseService executionLeaseService,
                        AgentRunLifecycleService runLifecycleService) {
        this.mapper = mapper;
        this.prefixEvidenceService = prefixEvidenceService;
        this.executionLeaseService = executionLeaseService;
        this.runLifecycleService = runLifecycleService;
    }

    public TokenTracker(AgentTokenUsageMapper mapper, AgentRunConfigSnapshotService prefixEvidenceService,
                        AgentRunExecutionLeaseService executionLeaseService) {
        this(mapper, prefixEvidenceService, executionLeaseService, null);
    }

    public TokenTracker(AgentTokenUsageMapper mapper, AgentRunConfigSnapshotService prefixEvidenceService) {
        this(mapper, prefixEvidenceService, null);
    }

    public TokenTracker(AgentTokenUsageMapper mapper) {
        this(mapper, null, null);
    }

    public void record(String conversationId, String sessionId, Integer studentId, Integer projectId,
                       String provider, String model, int promptTokens, int completionTokens,
                       int totalTokens, int iteration, String toolName) {
        record(conversationId, sessionId, studentId, projectId, provider, model,
                promptTokens, completionTokens, totalTokens, 0, 0,
                CacheTelemetryStatus.NOT_REPORTED, iteration, toolName);
    }

    public void record(String conversationId, String sessionId, Integer studentId, Integer projectId,
                       String provider, String model, int promptTokens, int completionTokens,
                       int totalTokens, int cachedTokens, int cacheWriteTokens, int iteration, String toolName) {
        record(conversationId, sessionId, studentId, projectId, provider, model,
                promptTokens, completionTokens, totalTokens, cachedTokens, cacheWriteTokens,
                CacheTelemetryStatus.NOT_REPORTED, iteration, toolName);
    }

    public void record(String conversationId, String sessionId, Integer studentId, Integer projectId,
                       String provider, String model, int promptTokens, int completionTokens,
                       int totalTokens, int cachedTokens, int cacheWriteTokens,
                       CacheTelemetryStatus cacheStatus, int iteration, String toolName) {
        record(conversationId, sessionId, studentId, projectId, provider, model,
                promptTokens, completionTokens, totalTokens, cachedTokens, cacheWriteTokens, 0, 0,
                cacheStatus, iteration, toolName);
    }

    public void record(String conversationId, String sessionId, Integer studentId, Integer projectId,
                       String provider, String model, int promptTokens, int completionTokens,
                       int totalTokens, int cachedTokens, int cacheWriteTokens,
                       int cacheHitTokens, int cacheMissTokens,
                       CacheTelemetryStatus cacheStatus, int iteration, String toolName) {
        AgentTokenUsage usage = new AgentTokenUsage(conversationId, sessionId, studentId, projectId,
                provider, model, promptTokens, completionTokens, totalTokens, iteration, toolName);
        usage.setCachedTokens(cachedTokens);
        usage.setCacheWriteTokens(cacheWriteTokens);
        usage.setCacheHitTokens(cacheHitTokens);
        usage.setCacheMissTokens(cacheMissTokens);
        usage.setCacheStatus((cacheStatus == null ? CacheTelemetryStatus.NOT_REPORTED : cacheStatus).value());
        usage.setTaskId(null);
        usage.setExecutionEpoch(null);
        insert(usage);
    }

    /** Persists usage only while the executor still holds the task/epoch write fence. */
    @Transactional(rollbackFor = Exception.class)
    public void recordWithFence(ExecutionFence fence, String conversationId, String sessionId,
                                Integer studentId, Integer projectId, String provider, String model,
                                int promptTokens, int completionTokens, int totalTokens,
                                int cachedTokens, int cacheWriteTokens, int cacheHitTokens,
                                int cacheMissTokens, CacheTelemetryStatus cacheStatus,
                                int iteration, String toolName) {
        requireWriteFence(fence);
        AgentTokenUsage usage = new AgentTokenUsage(conversationId, sessionId, studentId, projectId,
                provider, model, promptTokens, completionTokens, totalTokens, iteration, toolName);
        usage.setCachedTokens(cachedTokens);
        usage.setCacheWriteTokens(cacheWriteTokens);
        usage.setCacheHitTokens(cacheHitTokens);
        usage.setCacheMissTokens(cacheMissTokens);
        usage.setCacheStatus((cacheStatus == null ? CacheTelemetryStatus.NOT_REPORTED : cacheStatus).value());
        usage.setTaskId(fence.taskId());
        usage.setExecutionEpoch(fence.epoch());
        insert(usage);
    }

    public void recordFromMap(String conversationId, String sessionId, Integer studentId, Integer projectId,
                              String provider, String model, Map<String, Object> usageMap, int iteration,
                              String toolName) {
        recordFromMap(conversationId, sessionId, studentId, projectId, provider, model, usageMap,
                CacheTelemetryStatus.NOT_REPORTED, iteration, toolName);
    }

    public void recordFromMap(String conversationId, String sessionId, Integer studentId, Integer projectId,
                              String provider, String model, Map<String, Object> usageMap,
                              CacheTelemetryStatus cacheStatus, int iteration, String toolName) {
        recordFromMapInternal(null, conversationId, sessionId, studentId, projectId, provider, model,
                usageMap, cacheStatus, iteration, toolName);
    }

    /** Fenced counterpart used by the durable Agent loop. */
    @Transactional(rollbackFor = Exception.class)
    public void recordFromMapWithFence(ExecutionFence fence, String conversationId, String sessionId,
                                       Integer studentId, Integer projectId, String provider, String model,
                                       Map<String, Object> usageMap, CacheTelemetryStatus cacheStatus,
                                       int iteration, String toolName) {
        recordFromMapInternal(fence, conversationId, sessionId, studentId, projectId, provider, model,
                usageMap, cacheStatus, iteration, toolName);
    }

    /** Persists the usage row and its durable TOKEN_USAGE event in one lifecycle transaction. */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunEvent recordFromMapWithFenceAndEvent(
            ExecutionFence fence, String conversationId, String sessionId,
            Integer studentId, Integer projectId, String provider, String model,
            Map<String, Object> usageMap, CacheTelemetryStatus cacheStatus, int iteration,
            String toolName, Supplier<Map<String, Object>> payloadSupplier, String idempotencyKey) {
        if (usageMap == null) return null;
        if (runLifecycleService == null) {
            throw new IllegalStateException("Agent run lifecycle service is unavailable");
        }
        Map<String, Object> normalized = CacheTelemetry.normalizeUsage(usageMap);
        int prompt = getInt(normalized, "prompt_tokens");
        int completion = getInt(normalized, "completion_tokens");
        int total = getInt(normalized, "total_tokens");
        int cached = getInt(normalized, "cached_tokens");
        int cacheWrite = getInt(normalized, "cache_write_tokens");
        int cacheHit = getInt(normalized, "cache_hit_tokens");
        int cacheMiss = getInt(normalized, "cache_miss_tokens");
        if (cacheHit == 0) cacheHit = cached;
        if (cacheMiss == 0) cacheMiss = cacheWrite;
        if (total == 0) total = prompt + completion;
        if (total <= 0) return null;

        requireWriteFence(fence);
        AgentTokenUsage usage = new AgentTokenUsage(conversationId, sessionId, studentId, projectId,
                provider, model, prompt, completion, total, iteration, toolName);
        usage.setCachedTokens(cached);
        usage.setCacheWriteTokens(cacheWrite);
        usage.setCacheHitTokens(cacheHit);
        usage.setCacheMissTokens(cacheMiss);
        usage.setCacheStatus((cacheStatus == null ? CacheTelemetryStatus.NOT_REPORTED : cacheStatus).value());
        return runLifecycleService.appendTokenUsage(fence, fence.taskId(), usage, payloadSupplier, idempotencyKey);
    }

    private void recordFromMapInternal(ExecutionFence fence, String conversationId, String sessionId,
                                       Integer studentId, Integer projectId, String provider, String model,
                                       Map<String, Object> usageMap, CacheTelemetryStatus cacheStatus,
                                       int iteration, String toolName) {
        if (usageMap == null) return;
        Map<String, Object> normalized = CacheTelemetry.normalizeUsage(usageMap);
        int prompt = getInt(normalized, "prompt_tokens");
        int completion = getInt(normalized, "completion_tokens");
        int total = getInt(normalized, "total_tokens");
        int cached = getInt(normalized, "cached_tokens");
        int cacheWrite = getInt(normalized, "cache_write_tokens");
        int cacheHit = getInt(normalized, "cache_hit_tokens");
        int cacheMiss = getInt(normalized, "cache_miss_tokens");
        if (cacheHit == 0) cacheHit = cached;
        if (cacheMiss == 0) cacheMiss = cacheWrite;
        if (total == 0) total = prompt + completion;
        if (total > 0) {
            if (fence == null) {
                record(conversationId, sessionId, studentId, projectId, provider, model,
                        prompt, completion, total, cached, cacheWrite, cacheHit, cacheMiss,
                        cacheStatus, iteration, toolName);
            } else {
                recordWithFence(fence, conversationId, sessionId, studentId, projectId, provider, model,
                        prompt, completion, total, cached, cacheWrite, cacheHit, cacheMiss,
                        cacheStatus, iteration, toolName);
            }
        }
    }

    /**
     * 按会话聚合的用量摘要：用量面板「会话明细统计」的持久化读模型。
     *
     * <p>GROUP BY 下推到 SQL（见 {@code AgentTokenUsageMapper.selectConversationSummaries}），
     * 本方法只做数值归一化——与 {@link #getStudentStats} 同口径：负数/缺列归零、忽略空会话 ID。
     * 只读，不写入任何事实源。
     *
     * @return 按最近使用时间倒序的摘要；每项含 conversationId / promptTokens / completionTokens /
     *         totalTokens / callCount / lastUsedAt（标题由调用方补齐，本层不查会话表）
     */
    public List<Map<String, Object>> getConversationSummaries(Integer studentId, Integer projectId) {
        if (studentId == null || projectId == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = mapper.selectConversationSummaries(studentId, projectId);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String conversationId = textOf(row, "conversationId");
            if (conversationId == null || conversationId.isBlank()) continue;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("conversationId", conversationId);
            summary.put("promptTokens", valueOf(row, "promptTokens"));
            summary.put("completionTokens", valueOf(row, "completionTokens"));
            summary.put("totalTokens", valueOf(row, "totalTokens"));
            summary.put("callCount", valueOf(row, "callCount"));
            summary.put("lastUsedAt", textOf(row, "lastUsedAt"));
            summaries.add(summary);
        }
        return summaries;
    }

    /**
     * 聚合结果的键名容错读取：驱动/别名大小写差异不应导致整列丢失。
     * 这些是只读展示数据，缺列按 0 处理，绝不抛异常。
     */
    private Object rawValue(Map<String, Object> row, String key) {
        if (row == null) return null;
        if (row.containsKey(key)) return row.get(key);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private int valueOf(Map<String, Object> row, String key) {
        Object raw = rawValue(row, key);
        if (raw instanceof Number number) return Math.max(0, number.intValue());
        if (raw instanceof String text) {
            try {
                return Math.max(0, (int) Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String textOf(Map<String, Object> row, String key) {
        Object raw = rawValue(row, key);
        return raw == null ? null : String.valueOf(raw);
    }

    public Map<String, Object> getStudentStats(Integer studentId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("student_id", studentId);
        List<AgentTokenUsage> list = mapper.selectList(qw);

        int totalPrompt = list.stream().mapToInt(u -> value(u.getPromptTokens())).sum();
        int totalCompletion = list.stream().mapToInt(u -> value(u.getCompletionTokens())).sum();
        int totalTokens = list.stream().mapToInt(u -> value(u.getTotalTokens())).sum();
        int totalCached = list.stream().mapToInt(u -> value(u.getCachedTokens())).sum();
        int totalCacheWrite = list.stream().mapToInt(u -> value(u.getCacheWriteTokens())).sum();
        int totalCacheHit = list.stream().mapToInt(u -> value(u.getCacheHitTokens())).sum();
        int totalCacheMiss = list.stream().mapToInt(u -> value(u.getCacheMissTokens())).sum();
        CacheAggregate cache = aggregateCache(list);
        Map<String, Integer> byModel = list.stream()
                .filter(u -> u.getModel() != null)
                .collect(Collectors.groupingBy(AgentTokenUsage::getModel,
                        Collectors.summingInt(u -> value(u.getTotalTokens()))));

        Map<String, List<AgentTokenUsage>> usagesByModel = list.stream()
                .filter(u -> u.getModel() != null && !u.getModel().isBlank())
                .collect(Collectors.groupingBy(
                        AgentTokenUsage::getModel,
                        TreeMap::new,
                        Collectors.toList()));
        Map<String, Object> cacheByModel = new TreeMap<>();
        usagesByModel.forEach((model, modelUsages) -> cacheByModel.put(model, cacheStats(modelUsages)));
        Map<String, List<AgentTokenUsage>> usagesByDay = list.stream()
                .filter(u -> u.getCreateTime() != null)
                .collect(Collectors.groupingBy(
                        u -> u.getCreateTime().toLocalDate().toString(),
                        TreeMap::new,
                        Collectors.toList()));
        Map<String, Integer> byDay = new TreeMap<>();
        Map<String, Object> cacheByDay = new TreeMap<>();
        usagesByDay.forEach((date, dayUsages) -> {
            byDay.put(date, dayUsages.stream().mapToInt(u -> value(u.getTotalTokens())).sum());
            CacheAggregate dailyCache = aggregateCache(dayUsages);
            if (dailyCache.reportedCallCount() == 0) return;
            Map<String, Object> dailyCacheStats = new LinkedHashMap<>();
            dailyCacheStats.put("cacheStatus", dailyCache.status().value());
            dailyCacheStats.put("cacheTelemetryCallCount", dailyCache.reportedCallCount());
            dailyCacheStats.put("cacheHitRate", dailyCache.hitRate());
            cacheByDay.put(date, dailyCacheStats);
        });

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("studentId", studentId);
        stats.put("totalPromptTokens", totalPrompt);
        stats.put("totalCompletionTokens", totalCompletion);
        stats.put("totalTokens", totalTokens);
        stats.put("totalCachedTokens", totalCached);
        stats.put("totalCacheWriteTokens", totalCacheWrite);
        stats.put("totalCacheHitTokens", totalCacheHit);
        stats.put("totalCacheMissTokens", totalCacheMiss);
        stats.put("cacheStatus", cache.status().value());
        stats.put("cacheTelemetryCallCount", cache.reportedCallCount());
        stats.put("cacheHitRate", cache.hitRate());
        if (this.prefixEvidenceService != null) {
            stats.putAll(this.prefixEvidenceService.prefixStatsForStudent(studentId).toPayload());
        }
        stats.put("callCount", list.size());
        stats.put("byModel", byModel);
        stats.put("cacheByModel", cacheByModel);
        stats.put("byDay", byDay);
        stats.put("cacheByDay", cacheByDay);
        return stats;
    }

    private Map<String, Object> cacheStats(List<AgentTokenUsage> usages) {
        CacheAggregate cache = aggregateCache(usages);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPromptTokens", usages.stream().mapToInt(u -> value(u.getPromptTokens())).sum());
        stats.put("totalCachedTokens", usages.stream().mapToInt(u -> value(u.getCachedTokens())).sum());
        stats.put("totalCacheWriteTokens", usages.stream().mapToInt(u -> value(u.getCacheWriteTokens())).sum());
        stats.put("totalCacheHitTokens", usages.stream().mapToInt(u -> value(u.getCacheHitTokens())).sum());
        stats.put("totalCacheMissTokens", usages.stream().mapToInt(u -> value(u.getCacheMissTokens())).sum());
        stats.put("cacheStatus", cache.status().value());
        stats.put("cacheTelemetryCallCount", cache.reportedCallCount());
        stats.put("cacheHitRate", cache.hitRate());
        return stats;
    }

    private CacheAggregate aggregateCache(List<AgentTokenUsage> usages) {
        int reportedPrompt = 0;
        int reportedCached = 0;
        int reportedCacheTokens = 0;
        int reportedCalls = 0;
        boolean anyWriteOnly = false;
        boolean anyMiss = false;
        boolean allDisabled = !usages.isEmpty();
        for (AgentTokenUsage usage : usages) {
            CacheTelemetryStatus status = statusOf(usage);
            allDisabled &= status == CacheTelemetryStatus.DISABLED;
            if (!isReported(status)) continue;
            reportedCalls++;
            reportedPrompt += value(usage.getPromptTokens());
            reportedCached += value(usage.getCachedTokens());
            reportedCacheTokens += value(usage.getCacheHitTokens()) + value(usage.getCacheMissTokens());
            anyWriteOnly |= status == CacheTelemetryStatus.WRITE_ONLY;
            anyMiss |= status == CacheTelemetryStatus.MISS;
        }
        CacheTelemetryStatus status = (reportedCached > 0) ? CacheTelemetryStatus.HIT
                : anyWriteOnly ? CacheTelemetryStatus.WRITE_ONLY
                : anyMiss ? CacheTelemetryStatus.MISS
                : allDisabled ? CacheTelemetryStatus.DISABLED
                : CacheTelemetryStatus.NOT_REPORTED;
        // 分母与逐次 CacheTelemetry.hitRate 保持同一规则：provider 明确上报 hit/miss 时用
        // hit+miss（缓存口径），否则回落到全量 prompt。取 max 保证既有口径数字不变——
        // 只有"显式 miss 使 hit+miss 大于 prompt"的场景会从原先偏高的比率被纠正。
        int denominator = Math.max(reportedPrompt, reportedCacheTokens);
        Double hitRate = (denominator <= 0 || reportedCached <= 0)
                ? null
                : Math.min(100.0, Math.round(reportedCached * 10000.0 / denominator) / 100.0);
        return new CacheAggregate(status, reportedCalls, hitRate);
    }

    private boolean isReported(CacheTelemetryStatus status) {
        return status == CacheTelemetryStatus.HIT
                || status == CacheTelemetryStatus.MISS
                || status == CacheTelemetryStatus.WRITE_ONLY;
    }

    private CacheTelemetryStatus statusOf(AgentTokenUsage usage) {
        return CacheTelemetryStatus.fromValue(usage == null ? null : usage.getCacheStatus());
    }

    private int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number) return Math.max(0, ((Number) v).intValue());
        if (v instanceof String) {
            try { return Math.max(0, Integer.parseInt((String) v)); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private void requireWriteFence(ExecutionFence fence) {
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for fenced token usage writes");
        }
        if (executionLeaseService == null) {
            throw new IllegalStateException("Execution lease service is unavailable");
        }
        executionLeaseService.requireActiveFenceForWrite(fence, LocalDateTime.now());
    }

    private void insert(AgentTokenUsage usage) {
        mapper.insert(usage);
    }

    private record CacheAggregate(CacheTelemetryStatus status, int reportedCallCount, Double hitRate) {
    }
}
