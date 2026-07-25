package com.labex.labexagent.commandsecurity;

import java.util.Objects;

/**
 * Immutable server-owned execution intent. The request intentionally contains no approval state.
 */
public record CommandRequest(
        String command,
        String shell,
        String workingDirectory,
        int timeoutSeconds,
        boolean longRunning,
        boolean networkRequested,
        String sandboxProfile
) {
    public CommandRequest {
        command = command == null ? "" : command;
        shell = normalizeRequired(shell, "direct");
        workingDirectory = workingDirectory == null ? "." : workingDirectory;
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        sandboxProfile = normalizeRequired(sandboxProfile, "default");
    }

    public CommandRequest(String command) {
        this(command, "direct", ".", 60, false, false, "default");
    }

    private static String normalizeRequired(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, fallback).trim();
        return normalized.isEmpty() ? fallback : normalized;
    }
}
