package com.labex.labexagent.runtime;

/** Chooses the least destructive action required to return a prompt to its configured safe budget. */
public final class ContextWindowSupervisor {

    public enum Action {
        NONE,
        PRUNE,
        CHECKPOINT
    }

    public record Decision(Action action, int estimatedTokens, int softLimitTokens, int tokensOverSoftLimit) {
        public boolean requiresAction() {
            return action != Action.NONE;
        }
    }

    public Decision decide(ContextWindowPolicy policy, int estimatedTokens, boolean hasPrunableToolResult) {
        if (policy == null || !policy.autoCompactionEnabled() || estimatedTokens < policy.softLimitTokens()) {
            return new Decision(Action.NONE, estimatedTokens, policy == null ? 0 : policy.softLimitTokens(), 0);
        }
        Action action = policy.pruningEnabled() && hasPrunableToolResult ? Action.PRUNE : Action.CHECKPOINT;
        return new Decision(action, estimatedTokens, policy.softLimitTokens(), estimatedTokens - policy.softLimitTokens());
    }
}