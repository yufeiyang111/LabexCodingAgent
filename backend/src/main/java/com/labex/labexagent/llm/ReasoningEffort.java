package com.labex.labexagent.llm;

import java.util.Locale;
import java.util.Set;

/** Valid OpenCode-style reasoning effort values accepted by the model configuration API. */
public final class ReasoningEffort {
    private static final Set<String> VALUES = Set.of("low", "medium", "high", "xhigh");

    private ReasoningEffort() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "medium";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!VALUES.contains(normalized)) {
            throw new IllegalArgumentException("reasoningEffort must be one of: low, medium, high, xhigh");
        }
        return normalized;
    }

    public static boolean isValid(String value) {
        try {
            normalize(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
