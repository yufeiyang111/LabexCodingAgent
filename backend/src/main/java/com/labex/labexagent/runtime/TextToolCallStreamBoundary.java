package com.labex.labexagent.runtime;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 在模型文本流开头识别显式工具信封，避免把兼容协议标签投影成用户可见答案。
 * 普通文本一旦排除协议前缀就立即透传，不等待整轮结束。
 */
final class TextToolCallStreamBoundary {
    private static final List<String> EXPLICIT_PREFIXES = List.of(
            "<tool_call",
            "<minimax:tool_call",
            "<invoke",
            "<minimax:invoke",
            "```tool_call");

    private final Consumer<String> visibleSink;
    private final StringBuilder pending = new StringBuilder();
    private Mode mode = Mode.UNDECIDED;

    TextToolCallStreamBoundary(Consumer<String> visibleSink) {
        this.visibleSink = visibleSink;
    }

    void push(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (mode == Mode.PASSTHROUGH) {
            visibleSink.accept(delta);
            return;
        }
        if (mode == Mode.ENVELOPE) {
            return;
        }

        pending.append(delta);
        String prefix = canonicalPrefix(pending.toString());
        if (prefix.isEmpty()) {
            return;
        }
        if (hasCompleteExplicitPrefix(prefix)) {
            mode = Mode.ENVELOPE;
            pending.setLength(0);
            return;
        }
        if (couldStillBecomeExplicitPrefix(prefix)) {
            return;
        }
        mode = Mode.PASSTHROUGH;
        flushPending();
    }

    void finish() {
        if (mode != Mode.UNDECIDED) {
            return;
        }
        ToolCallExtractor.Extraction extraction = ToolCallExtractor.extract(pending.toString());
        mode = extraction.status() == ToolCallExtractor.Status.NONE ? Mode.PASSTHROUGH : Mode.ENVELOPE;
        if (mode == Mode.PASSTHROUGH) {
            flushPending();
        } else {
            pending.setLength(0);
        }
    }

    private void flushPending() {
        if (pending.isEmpty()) {
            return;
        }
        String value = pending.toString();
        pending.setLength(0);
        visibleSink.accept(value);
    }

    private boolean hasCompleteExplicitPrefix(String prefix) {
        for (String candidate : EXPLICIT_PREFIXES) {
            if (!prefix.startsWith(candidate) || prefix.length() <= candidate.length()) {
                continue;
            }
            char delimiter = prefix.charAt(candidate.length());
            if (Character.isWhitespace(delimiter)
                    || (!candidate.startsWith("```") && delimiter == '>')) {
                return true;
            }
        }
        return false;
    }

    private boolean couldStillBecomeExplicitPrefix(String prefix) {
        return EXPLICIT_PREFIXES.stream().anyMatch(candidate ->
                candidate.startsWith(prefix) || candidate.equals(prefix));
    }
    private String canonicalPrefix(String value) {
        String normalized = value.stripLeading().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("<")) {
            return normalized;
        }
        int index = 1;
        while (index < normalized.length() && Character.isWhitespace(normalized.charAt(index))) {
            index++;
        }
        return "<" + normalized.substring(index);
    }

    private enum Mode {
        UNDECIDED,
        PASSTHROUGH,
        ENVELOPE
    }
}
