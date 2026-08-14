package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTokenUsage;
import com.labex.labexagent.llm.CacheTelemetryStatus;
import com.labex.mapper.AgentTokenUsageMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TokenTracker {

    private final AgentTokenUsageMapper mapper;

    public TokenTracker(AgentTokenUsageMapper mapper) {
        this.mapper = mapper;
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
        mapper.insert(usage);
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
        if (usageMap == null) return;
        int prompt = getInt(usageMap, "prompt_tokens");
        int completion = getInt(usageMap, "completion_tokens");
        int total = getInt(usageMap, "total_tokens");
        int cached = getInt(usageMap, "cached_tokens");
        int cacheWrite = getInt(usageMap, "cache_write_tokens");
        int cacheHit = getInt(usageMap, "cache_hit_tokens");
        int cacheMiss = getInt(usageMap, "cache_miss_tokens");
        if (cacheHit == 0) cacheHit = cached;
        if (cacheMiss == 0) cacheMiss = cacheWrite;
        if (total == 0) total = prompt + completion;
        if (total > 0) {
            record(conversationId, sessionId, studentId, projectId, provider, model,
                    prompt, completion, total, cached, cacheWrite, cacheHit, cacheMiss,
                    cacheStatus, iteration, toolName);
        }
    }

    public int getTotalTokensByConversation(String conversationId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        List<AgentTokenUsage> list = mapper.selectList(qw);
        return list.stream().mapToInt(u -> value(u.getTotalTokens())).sum();
    }

    public int getTotalTokensBySession(String sessionId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId);
        List<AgentTokenUsage> list = mapper.selectList(qw);
        return list.stream().mapToInt(u -> value(u.getTotalTokens())).sum();
    }

    public Map<String, Object> getConversationStats(String conversationId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        List<AgentTokenUsage> list = mapper.selectList(qw);

        int totalPrompt = list.stream().mapToInt(u -> value(u.getPromptTokens())).sum();
        int totalCompletion = list.stream().mapToInt(u -> value(u.getCompletionTokens())).sum();
        int totalTokens = list.stream().mapToInt(u -> value(u.getTotalTokens())).sum();
        int totalCached = list.stream().mapToInt(u -> value(u.getCachedTokens())).sum();
        int totalCacheWrite = list.stream().mapToInt(u -> value(u.getCacheWriteTokens())).sum();
        int totalCacheHit = list.stream().mapToInt(u -> value(u.getCacheHitTokens())).sum();
        int totalCacheMiss = list.stream().mapToInt(u -> value(u.getCacheMissTokens())).sum();
        CacheAggregate cache = aggregateCache(list);

        Map<String, Integer> byTool = list.stream()
                .filter(u -> u.getToolName() != null)
                .collect(Collectors.groupingBy(AgentTokenUsage::getToolName,
                        Collectors.summingInt(u -> value(u.getTotalTokens()))));

        List<Map<String, Object>> perIteration = list.stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("iteration", u.getIteration());
                    m.put("promptTokens", value(u.getPromptTokens()));
                    m.put("completionTokens", value(u.getCompletionTokens()));
                    m.put("totalTokens", value(u.getTotalTokens()));
                    m.put("cachedTokens", value(u.getCachedTokens()));
                    m.put("cacheWriteTokens", value(u.getCacheWriteTokens()));
                    m.put("cacheHitTokens", value(u.getCacheHitTokens()));
                    m.put("cacheMissTokens", value(u.getCacheMissTokens()));
                    m.put("cacheStatus", statusOf(u).value());
                    m.put("toolName", u.getToolName());
                    m.put("time", u.getCreateTime() != null ? u.getCreateTime().toString() : null);
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("conversationId", conversationId);
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
        stats.put("callCount", list.size());
        stats.put("byTool", byTool);
        stats.put("perIteration", perIteration);
        return stats;
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
        int reportedCalls = 0;
        boolean anyHit = false;
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
            anyHit |= status == CacheTelemetryStatus.HIT;
            anyWriteOnly |= status == CacheTelemetryStatus.WRITE_ONLY;
            anyMiss |= status == CacheTelemetryStatus.MISS;
        }
        CacheTelemetryStatus status = anyHit ? CacheTelemetryStatus.HIT
                : anyWriteOnly ? CacheTelemetryStatus.WRITE_ONLY
                : anyMiss ? CacheTelemetryStatus.MISS
                : allDisabled ? CacheTelemetryStatus.DISABLED
                : CacheTelemetryStatus.NOT_REPORTED;
        Double hitRate = reportedPrompt <= 0
                ? null
                : Math.round(reportedCached * 10000.0 / reportedPrompt) / 100.0;
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
        return value == null ? 0 : value;
    }

    private int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private record CacheAggregate(CacheTelemetryStatus status, int reportedCallCount, Double hitRate) {
    }
}
