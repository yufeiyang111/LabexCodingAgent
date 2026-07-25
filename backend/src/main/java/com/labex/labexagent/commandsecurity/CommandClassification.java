package com.labex.labexagent.commandsecurity;

import java.util.Objects;

/** Pure, immutable result of classifying one normalized direct command request. */
public record CommandClassification(
        CommandDecision decision,
        CommandReasonCode reasonCode,
        CommandRiskClass riskClass,
        String policyVersion,
        String normalizerVersion,
        NormalizedCommand normalizedCommand
) {
    public CommandClassification {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(riskClass, "riskClass");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(normalizerVersion, "normalizerVersion");
        Objects.requireNonNull(normalizedCommand, "normalizedCommand");
    }

    public boolean allowed() {
        return decision == CommandDecision.ALLOW;
    }

    public boolean requiresApproval() {
        return decision == CommandDecision.REQUIRE_APPROVAL;
    }
}
