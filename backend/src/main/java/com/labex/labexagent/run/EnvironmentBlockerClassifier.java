package com.labex.labexagent.run;

import com.labex.labexagent.tool.ToolResult;
import java.util.Locale;
import java.util.Optional;

/** Identifies infrastructure failures that must not be repaired by changing project source or dependency metadata. */
public final class EnvironmentBlockerClassifier {
    private EnvironmentBlockerClassifier() { }

    public static Optional<Blocker> classify(String toolName, ToolResult result) {
        if (result == null || result.isSuccess() || !isDependencyExecutionTool(toolName)) {
            return Optional.empty();
        }
        String output = result.getContent() == null ? "" : result.getContent().toLowerCase(Locale.ROOT);
        if (containsAny(output, "unknown host", "could not resolve host", "temporary failure in name resolution",
                "getaddrinfo", "enotfound", "name or service not known")) {
            return Optional.of(new Blocker("DNS_UNAVAILABLE",
                    "Dependency repository host could not be resolved. Restore DNS/network access before retrying."));
        }
        if (containsAny(output, "network is unreachable", "connection timed out", "connect timed out",
                "connection refused", "failed to connect", "connection reset")) {
            return Optional.of(new Blocker("NETWORK_UNAVAILABLE",
                    "Dependency repository is unreachable. Restore network connectivity before retrying."));
        }
        return Optional.empty();
    }

    private static boolean isDependencyExecutionTool(String toolName) {
        if (toolName == null) return false;
        return switch (toolName.trim().toLowerCase(Locale.ROOT)) {
            case "run_tests", "run_command", "bash" -> true;
            default -> false;
        };
    }

    private static boolean containsAny(String content, String... markers) {
        for (String marker : markers) if (content.contains(marker)) return true;
        return false;
    }

    public record Blocker(String code, String detail) { }
}
