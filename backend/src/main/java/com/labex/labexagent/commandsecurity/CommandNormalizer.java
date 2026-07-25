package com.labex.labexagent.commandsecurity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Versioned canonicalization for command-approval binding. It normalizes presentation-only
 * whitespace but preserves every execution-affecting request field in the SHA-256 input.
 */
public final class CommandNormalizer {
    public static final String VERSION = "command-normalizer-v1";

    public NormalizedCommand normalize(CommandRequest request) {
        String canonicalCommand = canonicalCommand(request.command());
        String canonicalWorkingDirectory = canonicalWorkingDirectory(request.workingDirectory());
        String canonical = String.join("\n",
                "version=" + VERSION,
                "command=" + canonicalCommand,
                "shell=" + canonicalToken(request.shell()),
                "cwd=" + canonicalWorkingDirectory,
                "timeoutSeconds=" + request.timeoutSeconds(),
                "longRunning=" + request.longRunning(),
                "networkRequested=" + request.networkRequested(),
                "sandboxProfile=" + canonicalToken(request.sandboxProfile()));
        return new NormalizedCommand(
                VERSION,
                canonicalCommand,
                canonicalWorkingDirectory,
                redactForDisplay(canonicalCommand),
                sha256(canonical));
    }

    private String canonicalCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("[ \\t]+", " ");
    }

    private String canonicalWorkingDirectory(String workingDirectory) {
        String value = workingDirectory == null ? "." : workingDirectory.trim().replace('\\', '/');
        if (value.isEmpty() || ".".equals(value)) {
            return ".";
        }
        boolean absolute = value.startsWith("/");
        String[] segments = value.split("/");
        java.util.Deque<String> canonical = new java.util.ArrayDeque<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (canonical.isEmpty() || "..".equals(canonical.peekLast())) {
                    canonical.addLast(segment);
                } else {
                    canonical.removeLast();
                }
                continue;
            }
            canonical.addLast(segment);
        }
        String normalized = canonical.isEmpty() ? "." : String.join("/", canonical);
        return absolute ? "/" + normalized : normalized;
    }

    private String canonicalToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[ \\t]+", " ");
    }

    private String redactForDisplay(String command) {
        return command.replaceAll("(?i)(--?(?:token|password|secret|api[-_]?key)|authorization)=[^ ]+", "$1=<redacted>");
    }

    private String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
