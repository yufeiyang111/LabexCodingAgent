package com.labex.labexagent.runtime;

import java.util.List;

public record ContextAdmissionDecision(
        Action action,
        boolean providerInvocationAllowed,
        String reasonCode,
        String message,
        ContextBudgetBreakdown breakdown,
        List<String> remediation) {

    public ContextAdmissionDecision {
        action = action == null ? Action.BLOCK_STATIC_OVERFLOW : action;
        reasonCode = reasonCode == null ? "unknown" : reasonCode;
        message = message == null ? "" : message;
        remediation = List.copyOf(remediation == null ? List.of() : remediation);
    }

    /**
     * 门禁结论。这里只保留"放行"与两种结构化阻断——上下文管理阶段（占位化 / compaction）
     * 的待办动作不由门禁表达，避免与 {@code ContextWindowSupervisor} 的 PRUNE 判定重复。
     */
    public enum Action {
        PROCEED,
        BLOCK_STATIC_OVERFLOW,
        BLOCK_REDUCIBLE_OVERFLOW
    }
}
