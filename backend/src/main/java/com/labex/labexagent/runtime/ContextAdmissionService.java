package com.labex.labexagent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@org.springframework.stereotype.Service
public class ContextAdmissionService {
    private static final Set<String> STATIC_CATEGORIES = Set.of(
            "systemPrompt", "toolDefinitions", "fixedInstructions", "skillsAndInstructions");

    public ContextBudgetBreakdown breakdown(Map<String, Integer> categories,
                                             int contextWindowTokens,
                                             int reservedOutputTokens,
                                             int softLimitTokens) {
        LinkedHashMap<String, Integer> staticCategories = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> reducibleCategories = new LinkedHashMap<>();
        if (categories != null) {
            categories.forEach((key, value) -> {
                Map<String, Integer> target = STATIC_CATEGORIES.contains(key) ? staticCategories : reducibleCategories;
                target.put(key, Math.max(0, value == null ? 0 : value));
            });
        }
        int inputCapacity = Math.max(0, contextWindowTokens - Math.max(0, reservedOutputTokens));
        return new ContextBudgetBreakdown(contextWindowTokens, inputCapacity, reservedOutputTokens,
                staticCategories, reducibleCategories, softLimitTokens);
    }

    public ContextAdmissionDecision decide(ContextBudgetBreakdown breakdown) {
        return decide(breakdown, true, true);
    }

    public ContextAdmissionDecision decide(ContextBudgetBreakdown breakdown,
                                             boolean pruningEnabled,
                                             boolean autoCompactionEnabled) {
        if (breakdown.staticTokens() > breakdown.inputCapacityTokens()) {
            return new ContextAdmissionDecision(
                    ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW,
                    false,
                    "static_context_exceeds_input_capacity",
                    "\u7cfb\u7edf\u63d0\u793a\u8bcd\u548c\u5de5\u5177 schema \u7b49\u9759\u6001\u4e0a\u4e0b\u6587\u5df2\u8d85\u8fc7\u6a21\u578b\u8f93\u5165\u5bb9\u91cf\uff0c\u538b\u7f29\u5386\u53f2\u6d88\u606f\u65e0\u6cd5\u89e3\u51b3\u3002",
                    breakdown,
                    List.of("\u5207\u6362\u5230\u66f4\u5927\u4e0a\u4e0b\u6587\u7a97\u53e3\u7684\u6a21\u578b", "\u51cf\u5c11\u5f53\u524d\u6a21\u5f0f\u66b4\u9732\u7684\u5de5\u5177 schema", "\u7cbe\u7b80\u7cfb\u7edf\u6307\u4ee4\u6216\u6280\u80fd\u5185\u5bb9"));
        }
        if (breakdown.totalInputTokens() <= breakdown.softLimitTokens()) {
            return proceed(breakdown, "within_soft_limit");
        }
        if (breakdown.reducibleTokens() <= 0) {
            return new ContextAdmissionDecision(
                    ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW,
                    false,
                    "static_context_exceeds_soft_limit",
                    "\u5f53\u524d\u8bf7\u6c42\u6ca1\u6709\u53ef\u538b\u7f29\u7684\u5386\u53f2\u4e0a\u4e0b\u6587\uff0c\u65e0\u6cd5\u5728\u5b89\u5168\u9884\u7b97\u5185\u8c03\u7528\u6a21\u578b\u3002",
                    breakdown,
                    List.of("\u51cf\u5c11\u5de5\u5177 schema", "\u7cbe\u7b80\u7cfb\u7edf\u6307\u4ee4", "\u4f7f\u7528\u66f4\u5927\u4e0a\u4e0b\u6587\u7a97\u53e3"));
        }
        int pruneCandidates = breakdown.reducibleCategories().getOrDefault("toolResults", 0)
                + breakdown.reducibleCategories().getOrDefault("conversationMessages", 0);
        if (pruningEnabled && pruneCandidates > 0) {
            return new ContextAdmissionDecision(ContextAdmissionDecision.Action.PRUNE, false,
                    "reducible_history_over_soft_limit", "\u9700\u8981\u5148\u88c1\u526a\u65e7\u7684\u5de5\u5177\u7ed3\u679c\u6216\u5bf9\u8bdd\u6d88\u606f\u3002",
                    breakdown, List.of("\u81ea\u52a8\u88c1\u526a\u65e7\u5de5\u5177\u7ed3\u679c"));
        }
        if (autoCompactionEnabled) {
            return new ContextAdmissionDecision(ContextAdmissionDecision.Action.COMPACT, false,
                    "reducible_context_over_soft_limit", "\u9700\u8981\u5148\u538b\u7f29\u53ef\u7ea6\u51cf\u7684\u9879\u76ee\u4e0a\u4e0b\u6587\u548c\u5386\u53f2\u6d88\u606f\u3002",
                    breakdown, List.of("\u81ea\u52a8\u538b\u7f29\u5bf9\u8bdd\u4e0a\u4e0b\u6587"));
        }
        if (breakdown.totalInputTokens() > breakdown.inputCapacityTokens()) {
            return new ContextAdmissionDecision(ContextAdmissionDecision.Action.BLOCK_REDUCIBLE_OVERFLOW, false,
                    "reducible_context_exceeds_input_capacity", "\u5386\u53f2\u4e0a\u4e0b\u6587\u53ef\u538b\u7f29\uff0c\u4f46\u5f53\u524d\u914d\u7f6e\u7981\u7528\u4e86\u81ea\u52a8\u538b\u7f29\u3002",
                    breakdown, List.of("\u542f\u7528\u81ea\u52a8\u538b\u7f29", "\u624b\u52a8\u538b\u7f29\u5f53\u524d\u4f1a\u8bdd"));
        }
        return proceed(breakdown, "soft_limit_advisory_only");
    }

    /**
     * 上下文管理阶段已经运行后使用的最终门禁。自动压缩开启时，不能把压缩未降到容量内误报为配置禁用。
     */
    public ContextAdmissionDecision decideAfterContextManagement(ContextBudgetBreakdown breakdown,
                                                                  boolean autoCompactionEnabled) {
        ContextAdmissionDecision decision = decide(breakdown, false, false);
        if (!autoCompactionEnabled
                || decision.action() != ContextAdmissionDecision.Action.BLOCK_REDUCIBLE_OVERFLOW) {
            return decision;
        }
        return new ContextAdmissionDecision(ContextAdmissionDecision.Action.BLOCK_REDUCIBLE_OVERFLOW, false,
                "reducible_context_exceeds_input_capacity_after_auto_compaction",
                "自动压缩已启用，但本轮上下文管理未能将可压缩历史降到模型输入容量以内。",
                breakdown, List.of("查看 COMPACTION_FAILED 事件中的摘要失败原因",
                        "修正或重试当前会话的压缩摘要", "手动压缩当前会话后重试"));
    }

    private ContextAdmissionDecision proceed(ContextBudgetBreakdown breakdown, String reasonCode) {
        return new ContextAdmissionDecision(ContextAdmissionDecision.Action.PROCEED, true, reasonCode,
                "", breakdown, List.of());
    }
}
