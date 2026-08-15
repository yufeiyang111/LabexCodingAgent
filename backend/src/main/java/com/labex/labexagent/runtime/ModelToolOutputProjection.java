package com.labex.labexagent.runtime;

import com.labex.labexagent.run.AgentToolOutputProperties;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds a budgeted tool-output projection only at the Provider request boundary.
 * The durable AgentRunPart remains the authoritative raw output and supports read_tool_output paging.
 */
final class ModelToolOutputProjection {
    private final AgentToolOutputProperties properties;

    ModelToolOutputProjection(AgentToolOutputProperties properties) {
        this.properties = properties == null ? new AgentToolOutputProperties() : properties;
    }

    Result project(String toolName, String source, boolean success) {
        String text = source == null ? "" : source;
        if (text.isEmpty()) {
            return new Result("", false);
        }
        int totalBytes = utf8Bytes(text);
        int totalLines = lineCount(text);
        if (totalBytes <= properties.getModelMaxBytes() && totalLines <= properties.getModelMaxLines()) {
            return new Result(text, false);
        }

        Direction direction = shouldKeepTail(toolName, success) ? Direction.TAIL : Direction.HEAD;
        String lineBounded = direction == Direction.TAIL
                ? lastLines(text, properties.getModelMaxLines())
                : firstLines(text, properties.getModelMaxLines());
        String preview = fitUtf8Bytes(lineBounded, properties.getModelMaxBytes(), direction);
        int previewBytes = utf8Bytes(preview);
        String content = "[tool_result_pruned direction=" + direction.name().toLowerCase(Locale.ROOT)
                + " source_utf8_bytes=" + totalBytes
                + " source_lines=" + totalLines
                + " retained_utf8_bytes=" + previewBytes
                + "]\n" + preview
                + "\n[Model projection is partial. The complete durable tool output can be read in pages with read_tool_output.]";
        return new Result(content, true);
    }

    private boolean shouldKeepTail(String toolName, boolean success) {
        if (!success) {
            return true;
        }
        String normalized = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "shell", "bash", "run_command", "run_tests", "execute_code" -> true;
            default -> false;
        };
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String firstLines(String text, int maxLines) {
        if (maxLines < 1 || lineCount(text) <= maxLines) {
            return maxLines < 1 ? "" : text;
        }
        int newlines = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' && ++newlines == maxLines) {
                return text.substring(0, index + 1);
            }
        }
        return text;
    }

    private static String lastLines(String text, int maxLines) {
        int totalLines = lineCount(text);
        if (maxLines < 1 || totalLines <= maxLines) {
            return maxLines < 1 ? "" : text;
        }
        int linesToDiscard = totalLines - maxLines;
        int newlines = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n' && ++newlines == linesToDiscard) {
                return text.substring(index + 1);
            }
        }
        return text;
    }

    private static String fitUtf8Bytes(String text, int maxBytes, Direction direction) {
        if (utf8Bytes(text) <= maxBytes) {
            return text;
        }
        if (direction == Direction.HEAD) {
            int used = 0;
            int end = 0;
            while (end < text.length()) {
                int codePoint = text.codePointAt(end);
                int bytes = utf8Bytes(codePoint);
                if (used + bytes > maxBytes) {
                    break;
                }
                used += bytes;
                end += Character.charCount(codePoint);
            }
            return text.substring(0, end);
        }

        int used = 0;
        int start = text.length();
        while (start > 0) {
            int codePoint = text.codePointBefore(start);
            int bytes = utf8Bytes(codePoint);
            if (used + bytes > maxBytes) {
                break;
            }
            used += bytes;
            start -= Character.charCount(codePoint);
        }
        return text.substring(start);
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    record Result(String content, boolean truncated) {
    }

    private enum Direction {
        HEAD,
        TAIL
    }
}
