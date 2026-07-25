package com.labex.labexagent.commandsecurity;

/**
 * Produces bounded public command/output text. Canonical command intent remains server-side only.
 */
public final class CommandRedactor {
    private CommandRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String redacted = value
                .replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1<redacted>")
                .replaceAll("(?i)(--?(?:token|password|secret|api[-_]?key)|authorization)(?:=|\\s+)[^\\s]+", "$1=<redacted>")
                .replaceAll("(?i)(\"(?:token|password|secret|api[-_]?key)\"\\s*:\\s*\")[^\"]+(\")", "$1<redacted>$2")
                .replaceAll("(?i)(sk-[a-z0-9_-]{8,}|gh[pousr]_[a-z0-9]{8,})", "<redacted>")
                .replaceAll("(?s)-----BEGIN [A-Z ]+PRIVATE KEY-----.*?-----END [A-Z ]+PRIVATE KEY-----", "<redacted-private-key>");
        return redacted.length() <= 8_000 ? redacted : redacted.substring(0, 8_000) + "\n[output truncated]";
    }
}
