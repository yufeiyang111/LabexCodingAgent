package com.labex.labexagent.commandsecurity;

/**
 * Bounded output redactor for streams whose sensitive values may cross read boundaries. It retains
 * a short raw suffix only in memory; retained/public output is always redacted.
 */
public final class StatefulCommandRedactor {
    private static final int LOOKBEHIND_CHARS = 512;
    private final StringBuilder pending = new StringBuilder();

    public synchronized String append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        pending.append(chunk);
        if (pending.length() <= LOOKBEHIND_CHARS) {
            return "";
        }
        int safeLength = pending.length() - LOOKBEHIND_CHARS;
        String safePrefix = pending.substring(0, safeLength);
        pending.delete(0, safeLength);
        return CommandRedactor.redact(safePrefix);
    }

    /** Returns a redacted projection without discarding the suffix needed for a following chunk. */
    public synchronized String snapshotSuffix() {
        return CommandRedactor.redact(pending.toString());
    }

    /** Flushes the final redacted suffix after the producing process has closed. */
    public synchronized String finish() {
        String result = CommandRedactor.redact(pending.toString());
        pending.setLength(0);
        return result;
    }
}
