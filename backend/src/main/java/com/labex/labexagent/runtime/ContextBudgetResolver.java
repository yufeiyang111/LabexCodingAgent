package com.labex.labexagent.runtime;

import com.labex.entity.AgentModelConfig;
import java.util.OptionalInt;

public final class ContextBudgetResolver {
    private ContextBudgetResolver() { }
    public static OptionalInt resolveInputBudget(AgentModelConfig config) {
        if (config == null || config.getContextWindowTokens() == null || config.getContextWindowTokens() <= 0) return OptionalInt.empty();
        int output = config.getMaxTokens() == null ? 0 : Math.max(0, config.getMaxTokens());
        int input = config.getContextWindowTokens() - output;
        return input > 0 ? OptionalInt.of(input) : OptionalInt.empty();
    }
}
