package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTokenUsage;
import com.labex.mapper.AgentTokenUsageMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
                promptTokens, completionTokens, totalTokens, 0, 0, iteration, toolName);
    }

    public void record(String conversationId, String sessionId, Integer studentId, Integer projectId,
                       String provider, String model, int promptTokens, int completionTokens,
                       int totalTokens, int cachedTokens, int cacheWriteTokens, int iteration, String toolName) {
        AgentTokenUsage usage = new AgentTokenUsage(conversationId, sessionId, studentId, projectId,
                provider, model, promptTokens, completionTokens, totalTokens, iteration, toolName);
        usage.setCachedTokens(cachedTokens);
        usage.setCacheWriteTokens(cacheWriteTokens);
        mapper.insert(usage);
    }

    public void recordFromMap(String conversationId, String sessionId, Integer studentId, Integer projectId,
                               String provider, String model, Map<String, Object> usageMap, int iteration,
                               String toolName) {
        if (usageMap == null) return;
        int prompt = getInt(usageMap, "prompt_tokens");
        int completion = getInt(usageMap, "completion_tokens");
        int total = getInt(usageMap, "total_tokens");
        int cached = getInt(usageMap, "cached_tokens");
        int cacheWrite = getInt(usageMap, "cache_write_tokens");
        if (total == 0) total = prompt + completion;
        if (total > 0) {
            record(conversationId, sessionId, studentId, projectId, provider, model,
                    prompt, completion, total, cached, cacheWrite, iteration, toolName);
        }
    }

    public int getTotalTokensByConversation(String conversationId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        List<AgentTokenUsage> list = mapper.selectList(qw);
        return list.stream().mapToInt(AgentTokenUsage::getTotalTokens).sum();
    }

    public int getTotalTokensBySession(String sessionId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("session_id", sessionId);
        List<AgentTokenUsage> list = mapper.selectList(qw);
        return list.stream().mapToInt(AgentTokenUsage::getTotalTokens).sum();
    }

    public Map<String, Object> getConversationStats(String conversationId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("conversation_id", conversationId);
        List<AgentTokenUsage> list = mapper.selectList(qw);

        int totalPrompt = list.stream().mapToInt(AgentTokenUsage::getPromptTokens).sum();
        int totalCompletion = list.stream().mapToInt(AgentTokenUsage::getCompletionTokens).sum();
        int totalTokens = list.stream().mapToInt(AgentTokenUsage::getTotalTokens).sum();
        int totalCached = list.stream().mapToInt(u -> u.getCachedTokens() == null ? 0 : u.getCachedTokens()).sum();
        int totalCacheWrite = list.stream().mapToInt(u -> u.getCacheWriteTokens() == null ? 0 : u.getCacheWriteTokens()).sum();
        int callCount = list.size();

        Map<String, Integer> byTool = list.stream()
                .filter(u -> u.getToolName() != null)
                .collect(Collectors.groupingBy(AgentTokenUsage::getToolName,
                        Collectors.summingInt(AgentTokenUsage::getTotalTokens)));

        List<Map<String, Object>> perIteration = list.stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("iteration", u.getIteration());
                    m.put("promptTokens", u.getPromptTokens());
                    m.put("completionTokens", u.getCompletionTokens());
                    m.put("totalTokens", u.getTotalTokens());
                    m.put("cachedTokens", u.getCachedTokens() == null ? 0 : u.getCachedTokens());
                    m.put("cacheWriteTokens", u.getCacheWriteTokens() == null ? 0 : u.getCacheWriteTokens());
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
        stats.put("cacheHitRate", totalPrompt <= 0 ? 0.0 : Math.round(totalCached * 10000.0 / totalPrompt) / 100.0);
        stats.put("callCount", callCount);
        stats.put("byTool", byTool);
        stats.put("perIteration", perIteration);
        return stats;
    }

    public Map<String, Object> getStudentStats(Integer studentId) {
        QueryWrapper<AgentTokenUsage> qw = new QueryWrapper<>();
        qw.eq("student_id", studentId);
        List<AgentTokenUsage> list = mapper.selectList(qw);

        int totalTokens = list.stream().mapToInt(AgentTokenUsage::getTotalTokens).sum();
        int totalPrompt = list.stream().mapToInt(AgentTokenUsage::getPromptTokens).sum();
        int totalCached = list.stream().mapToInt(u -> u.getCachedTokens() == null ? 0 : u.getCachedTokens()).sum();
        Map<String, Integer> byModel = list.stream()
                .filter(u -> u.getModel() != null)
                .collect(Collectors.groupingBy(AgentTokenUsage::getModel,
                        Collectors.summingInt(AgentTokenUsage::getTotalTokens)));

        Map<String, Integer> byDay = list.stream()
                .filter(u -> u.getCreateTime() != null)
                .collect(Collectors.groupingBy(
                        u -> u.getCreateTime().toLocalDate().toString(),
                        Collectors.summingInt(AgentTokenUsage::getTotalTokens)));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("studentId", studentId);
        stats.put("totalTokens", totalTokens);
        stats.put("totalCachedTokens", totalCached);
        stats.put("cacheHitRate", totalPrompt <= 0 ? 0.0 : Math.round(totalCached * 10000.0 / totalPrompt) / 100.0);
        stats.put("callCount", list.size());
        stats.put("byModel", byModel);
        stats.put("byDay", byDay);
        return stats;
    }

    private int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (Exception e) { return 0; }
        }
        return 0;
    }
}
