package com.labex.labexagent.runtime;

import java.util.Locale;
import java.util.Set;

/** 服务端认可的 Agent 运行模式；未知值必须拒绝，不能回退到可写模式。 */
public final class AgentMode {
    private static final Set<String> SUPPORTED = Set.of("agent", "build", "plan", "explore");

    private AgentMode() {
    }

    public static String normalize(String requestedMode) {
        if (requestedMode == null || requestedMode.isBlank()) {
            return "agent";
        }
        String normalized = requestedMode.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported agent mode: " + requestedMode);
        }
        return normalized;
    }

    public static boolean isSupported(String mode) {
        if (mode == null || mode.isBlank()) return false;
        return SUPPORTED.contains(mode.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isUnrestricted(String mode) {
        if (!isSupported(mode)) return false;
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return "agent".equals(normalized) || "build".equals(normalized);
    }
}