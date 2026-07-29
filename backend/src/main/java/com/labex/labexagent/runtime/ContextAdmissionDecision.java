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

    public enum Action {
        PROCEED,
        PRUNE,
        COMPACT,
        BLOCK_STATIC_OVERFLOW,
        BLOCK_REDUCIBLE_OVERFLOW
    }
}
