package com.labex.labexagent.commandsecurity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Versioned canonicalization for command-approval binding.
 * Direct-command compatibility keeps its historical whitespace normalization; real Shell payloads
 * preserve spaces, quotes and operators because those characters change Bash/PowerShell semantics.
 */
@Service
public class CommandNormalizer {
    public static final String VERSION = "command-normalizer-v1";

    public NormalizedCommand normalize(CommandRequest request) {
        String canonicalCommand = canonicalCommand(request.command(), request.shell());
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

    private String canonicalCommand(String command, String shell) {
        String value = command == null ? "" : command;
        if (isDirectShell(shell)) {
            return value.trim().replaceAll("[ \t]+", " ");
        }
        // Preserve complete multi-line shell text; only make Windows line endings stable for digesting.
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private boolean isDirectShell(String shell) {
        return shell == null || shell.isBlank() || "direct".equalsIgnoreCase(shell.trim());
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
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[ \t]+", " ");
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
