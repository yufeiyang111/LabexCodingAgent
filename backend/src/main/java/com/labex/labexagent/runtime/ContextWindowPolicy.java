package com.labex.labexagent.runtime;

import com.labex.entity.AgentModelConfig;
import java.util.Optional;

/** Model-level context management values derived from a validated configuration. */
public record ContextWindowPolicy(
        boolean autoCompactionEnabled,
        boolean pruningEnabled,
        int inputCapacityTokens,
        int reservedTokens,
        int softLimitTokens,
        int tailTurns,
        int preserveRecentTokens) {

    private static final int DEFAULT_TAIL_TURNS = 2;
    private static final int MIN_RESERVED_TOKENS = 2_048;
    private static final int MAX_RESERVED_TOKENS = 8_192;
    private static final int MIN_RECENT_TOKENS = 2_000;
    private static final int MAX_RECENT_TOKENS = 8_000;
    private static final int DEFAULT_THRESHOLD_PERCENT = 90;
    private static final int MIN_THRESHOLD_PERCENT = 70;
    private static final int MAX_THRESHOLD_PERCENT = 99;

    public static Optional<ContextWindowPolicy> from(AgentModelConfig config) {
        if (config == null || config.getContextWindowTokens() == null || config.getMaxTokens() == null) {
            return Optional.empty();
        }
        int contextWindow = config.getContextWindowTokens();
        int maxOutput = config.getMaxTokens();
        if (contextWindow <= maxOutput || maxOutput <= 0) {
            return Optional.empty();
        }

        int inputCapacity = contextWindow - maxOutput;
        int defaultReserved = clamp((int) Math.ceil(contextWindow * 0.10), MIN_RESERVED_TOKENS, MAX_RESERVED_TOKENS);
        int requestedReserved = config.getCompactionReservedTokens() == null
                ? defaultReserved : config.getCompactionReservedTokens();
        int maxReserve = Math.max(0, contextWindow - 1_024);
        int reserved = clamp(requestedReserved, 0, maxReserve);
        int requestedThreshold = config.getCompactionThresholdPercent() == null
                ? DEFAULT_THRESHOLD_PERCENT : config.getCompactionThresholdPercent();
        int thresholdPercent = clamp(requestedThreshold, MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT);
        int reserveLimit = contextWindow - reserved;
        int thresholdLimit = (int) Math.floor(contextWindow * (thresholdPercent / 100.0));
        int softLimit = Math.min(inputCapacity, Math.min(reserveLimit, thresholdLimit));
        int requestedTailTurns = config.getCompactionTailTurns() == null
                ? DEFAULT_TAIL_TURNS : config.getCompactionTailTurns();
        int tailTurns = Math.max(1, requestedTailTurns);
        int defaultRecent = clamp((int) Math.floor(softLimit * 0.25), MIN_RECENT_TOKENS, MAX_RECENT_TOKENS);
        int requestedRecent = config.getCompactionPreserveRecentTokens() == null
                ? defaultRecent : config.getCompactionPreserveRecentTokens();
        int recentTokens = clamp(requestedRecent, 1, Math.max(1, softLimit));
        boolean auto = config.getCompactionAuto() == null || config.getCompactionAuto() != 0;
        boolean prune = config.getCompactionPrune() != null && config.getCompactionPrune() != 0;
        return Optional.of(new ContextWindowPolicy(auto, prune, inputCapacity, reserved, softLimit, tailTurns, recentTokens));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}