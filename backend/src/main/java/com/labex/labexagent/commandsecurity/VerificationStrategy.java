package com.labex.labexagent.commandsecurity;

import java.util.Locale;

/** 验证命令策略，只允许服务端定义的直接 argv 目标。 */
public enum VerificationStrategy {
    AUTO,
    TEST,
    COMPILE,
    BUILD,
    OFFLINE_TEST,
    MANUAL;

    public static VerificationStrategy parse(String value, VerificationStrategy fallback) {
        VerificationStrategy safeFallback = fallback == null ? AUTO : fallback;
        if (value == null || value.isBlank()) return safeFallback;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "TESTS", "TESTS_FIRST" -> TEST;
            case "OFFLINE" -> OFFLINE_TEST;
            case "COMPILE_FIRST" -> COMPILE;
            case "BUILD_FIRST" -> BUILD;
            case "MANUAL_ONLY" -> MANUAL;
            default -> {
                try {
                    yield VerificationStrategy.valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    yield safeFallback;
                }
            }
        };
    }
}