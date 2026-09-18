package com.labex.labexagent.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provider 调用前的上下文准入判定。
 *
 * <p>唯一判定入口是 {@link #decideAfterContextManagement}：可约减历史的处理已由上下文管理阶段完成，
 * 门禁只负责给出"放行 / 结构化阻断"结论。</p>
 */
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

    /**
     * Provider 调用前的最终门禁。
     *
     * <p>可约减历史的处理（Provider 投影边界的占位化与 compaction）已由 {@code AgentLoopEngine}
     * 的上下文管理阶段完成，因此门禁只回答"现在能不能发"。这里刻意不再表达"PRUNE / COMPACT 待办动作"：
     * 那些动作各有权威实现（{@code ContextWindowSupervisor} 选动作、{@code TurnAwareContextPruner} 判据、
     * {@code AgentCompactionService} 落库），在门禁里再保留一份同义分支只会留下永不可达的第二套判定。</p>
     *
     * <p>判定顺序与历史行为等价：静态上下文超输入容量 → 未超软限放行 → 无可约减项 →
     * 可约减项在上下文管理后仍超输入容量 → 软限告警放行。</p>
     */
    public ContextAdmissionDecision decideAfterContextManagement(ContextBudgetBreakdown breakdown,
                                                                  boolean autoCompactionEnabled) {
        if (breakdown.staticTokens() > breakdown.inputCapacityTokens()) {
            return new ContextAdmissionDecision(
                    ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW,
                    false,
                    "static_context_exceeds_input_capacity",
                    "系统提示词和工具 schema 等静态上下文已超过模型输入容量，压缩历史消息无法解决。",
                    breakdown,
                    List.of("切换到更大上下文窗口的模型", "减少当前模式暴露的工具 schema", "精简系统指令或技能内容"));
        }
        if (breakdown.totalInputTokens() <= breakdown.softLimitTokens()) {
            return proceed(breakdown, "within_soft_limit");
        }
        if (breakdown.reducibleTokens() <= 0) {
            return new ContextAdmissionDecision(
                    ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW,
                    false,
                    "static_context_exceeds_soft_limit",
                    "当前请求没有可压缩的历史上下文，无法在安全预算内调用模型。",
                    breakdown,
                    List.of("减少工具 schema", "精简系统指令", "使用更大上下文窗口"));
        }
        if (breakdown.totalInputTokens() > breakdown.inputCapacityTokens()) {
            if (autoCompactionEnabled) {
                return new ContextAdmissionDecision(ContextAdmissionDecision.Action.BLOCK_REDUCIBLE_OVERFLOW, false,
                        "reducible_context_exceeds_input_capacity_after_auto_compaction",
                        "自动压缩已启用，但本轮上下文管理未能将可压缩历史降到模型输入容量以内。",
                        breakdown, List.of("查看 COMPACTION_FAILED 事件中的摘要失败原因",
                                "修正或重试当前会话的压缩摘要",
                                "手动压缩当前会话后重试"));
            }
            return new ContextAdmissionDecision(ContextAdmissionDecision.Action.BLOCK_REDUCIBLE_OVERFLOW, false,
                    "reducible_context_exceeds_input_capacity",
                    "历史上下文可压缩，但当前配置禁用了自动压缩。",
                    breakdown, List.of("启用自动压缩", "手动压缩当前会话"));
        }
        return proceed(breakdown, "soft_limit_advisory_only");
    }

    private ContextAdmissionDecision proceed(ContextBudgetBreakdown breakdown, String reasonCode) {
        return new ContextAdmissionDecision(ContextAdmissionDecision.Action.PROCEED, true, reasonCode,
                "", breakdown, List.of());
    }
}
