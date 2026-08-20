package com.labex.labexagent.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一约束 Provider 内部推理协议，避免标签进入用户可见输出和持久化回放。
 */
public final class InternalReasoningBoundary {
    private static final String RAW_TAG_ATTRIBUTES =
            "(?:\\s+(?:\"[^\"]*\"|'[^']*'|[^<>\"'])*)?";
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "(?is)\\\\?<\\s*/?\\s*think(?:ing)?" + RAW_TAG_ATTRIBUTES + "\\s*/?\\s*\\\\?>"
                    + "|\\\\?&lt;\\s*/?\\s*think(?:ing)?(?:\\s+.*?)?\\s*/?\\s*\\\\?&gt;"
                    + "|\\\\?&#(?:0*60|x0*3c);\\s*/?\\s*think(?:ing)?(?:\\s+.*?)?"
                    + "\\s*/?\\s*\\\\?&#(?:0*62|x0*3e);");

    private InternalReasoningBoundary() {
    }

    public static Projection project(String content, String dedicatedReasoning) {
        StringBuilder visible = new StringBuilder();
        StringBuilder embeddedReasoning = new StringBuilder();
        VisibleStreamFilter filter = new VisibleStreamFilter(embeddedReasoning::append, visible::append);
        filter.push(content);
        filter.flush();

        String normalizedDedicated = stripTags(dedicatedReasoning);
        String reasoning = normalizedDedicated.isBlank()
                ? embeddedReasoning.toString()
                : normalizedDedicated;
        return new Projection(visible.toString(), reasoning);
    }

    public static String stripVisible(String value) {
        return project(value, "").visible();
    }

    public static String stripTags(String value) {
        StringBuilder output = new StringBuilder();
        TagStreamFilter filter = new TagStreamFilter(output::append);
        filter.push(value);
        filter.flush();
        return output.toString();
    }

    /**
     * 在权威事件写入和 SSE 最终出口统一清洗内部推理协议。
     * FINAL 仅保留用户可见正文，THINK 系列保留推理文本但移除协议标签。
     */
    public static boolean requiresEventPayloadSanitization(String eventType) {
        return "FINAL".equalsIgnoreCase(eventType)
                || "FINAL_DELTA".equalsIgnoreCase(eventType)
                || "FINAL_CANDIDATE_DELTA".equalsIgnoreCase(eventType)
                || eventType != null && eventType.toUpperCase(Locale.ROOT).startsWith("THINK");
    }

    public static Object sanitizeEventPayload(String eventType, Object payload) {
        boolean finalProjection = "FINAL".equalsIgnoreCase(eventType)
                || "FINAL_DELTA".equalsIgnoreCase(eventType)
                || "FINAL_CANDIDATE_DELTA".equalsIgnoreCase(eventType);
        if (!requiresEventPayloadSanitization(eventType) || payload == null) {
            return payload;
        }
        if (payload instanceof String text) {
            return finalProjection ? stripVisible(text) : stripTags(text);
        }
        if (!(payload instanceof Map<?, ?> source)) {
            return payload;
        }

        LinkedHashMap<Object, Object> safe = new LinkedHashMap<>(source);
        for (String field : List.of("content", "delta", "message", "summary", "detail")) {
            Object value = safe.get(field);
            if (value instanceof String text) {
                boolean reasoningBody = !finalProjection && ("content".equals(field) || "delta".equals(field));
                safe.put(field, reasoningBody ? stripTags(text) : stripVisible(text));
            }
        }
        return safe;
    }

    public record Projection(String visible, String reasoning) {
    }

    /**
     * 普通 content 通道：推理块进入推理投影，其余文本进入可见投影。
     */
    public static final class VisibleStreamFilter {
        private final Consumer<String> onReasoning;
        private final Consumer<String> onVisible;
        private final StringBuilder buffer = new StringBuilder();
        private int reasoningDepth;

        public VisibleStreamFilter(Consumer<String> onReasoning, Consumer<String> onVisible) {
            this.onReasoning = onReasoning;
            this.onVisible = onVisible;
        }

        public void push(String value) {
            if (value == null || value.isEmpty()) return;
            buffer.append(value);
            process();
        }

        public void flush() {
            process();
            if (buffer.length() == 0) return;
            int safeLength = flushLength(buffer);
            emit(buffer.substring(0, safeLength));
            buffer.setLength(0);
        }

        private void process() {
            while (buffer.length() > 0) {
                TagMatch tag = findTag(buffer);
                if (tag != null) {
                    emit(buffer.substring(0, tag.index()));
                    buffer.delete(0, tag.end());
                    if (!tag.selfClosing()) {
                        reasoningDepth = tag.closing()
                                ? Math.max(0, reasoningDepth - 1)
                                : reasoningDepth + 1;
                    }
                    continue;
                }
                int safeLength = safeLength(buffer);
                emit(buffer.substring(0, safeLength));
                buffer.delete(0, safeLength);
                break;
            }
        }

        private void emit(String text) {
            if (text == null || text.isEmpty()) return;
            if (reasoningDepth > 0) onReasoning.accept(text);
            else onVisible.accept(text);
        }
    }

    /**
     * 独立 reasoning_content 通道：保留推理文本，只移除协议标签。
     */
    public static final class TagStreamFilter {
        private final Consumer<String> onText;
        private final StringBuilder buffer = new StringBuilder();

        public TagStreamFilter(Consumer<String> onText) {
            this.onText = onText;
        }

        public void push(String value) {
            if (value == null || value.isEmpty()) return;
            buffer.append(value);
            process();
        }

        public void flush() {
            process();
            if (buffer.length() == 0) return;
            int safeLength = flushLength(buffer);
            emit(buffer.substring(0, safeLength));
            buffer.setLength(0);
        }

        private void process() {
            while (buffer.length() > 0) {
                TagMatch tag = findTag(buffer);
                if (tag != null) {
                    emit(buffer.substring(0, tag.index()));
                    buffer.delete(0, tag.end());
                    continue;
                }
                int safeLength = safeLength(buffer);
                emit(buffer.substring(0, safeLength));
                buffer.delete(0, safeLength);
                break;
            }
        }

        private void emit(String text) {
            if (text != null && !text.isEmpty()) onText.accept(text);
        }
    }

    private static TagMatch findTag(CharSequence value) {
        Matcher matcher = TAG_PATTERN.matcher(value);
        if (!matcher.find()) return null;
        String syntax = normalizeTagSyntax(matcher.group());
        int index = 1;
        while (index < syntax.length() && Character.isWhitespace(syntax.charAt(index))) index++;
        boolean closing = index < syntax.length() && syntax.charAt(index) == '/';
        int end = syntax.length() - 2;
        while (end >= 0 && Character.isWhitespace(syntax.charAt(end))) end--;
        boolean selfClosing = end >= 0 && syntax.charAt(end) == '/';
        return new TagMatch(matcher.start(), matcher.end(), closing, selfClosing);
    }

    private static String normalizeTagSyntax(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.startsWith("\\")) normalized = normalized.substring(1);
        normalized = normalized.replaceFirst("(?is)^&lt;|^&#(?:0*60|x0*3c);", "<");
        normalized = normalized.replaceFirst("(?is)\\\\?&gt;$|\\\\?&#(?:0*62|x0*3e);$", ">");
        if (normalized.endsWith("\\>")) {
            normalized = normalized.substring(0, normalized.length() - 2) + ">";
        }
        return normalized;
    }

    private static int flushLength(CharSequence value) {
        return "\\".contentEquals(value) ? 1 : safeLength(value);
    }

    private static int safeLength(CharSequence value) {
        String text = value.toString();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if ((current == '<' || current == '&' || current == '\\')
                    && couldBeTagPrefix(text.substring(index))) {
                return index;
            }
        }
        return text.length();
    }

    private static boolean couldBeTagPrefix(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("\\")) {
            if (lower.length() == 1) return true;
            char next = lower.charAt(1);
            return (next == '<' || next == '&') && couldBeTagPrefix(lower.substring(1));
        }
        if ("&lt;".startsWith(lower) || "&#60;".startsWith(lower) || "&#x3c;".startsWith(lower)) {
            return true;
        }
        if (lower.startsWith("&lt;")) return couldBeRawPrefix("<" + lower.substring(4));
        if (lower.startsWith("&#60;")) return couldBeRawPrefix("<" + lower.substring(5));
        if (lower.startsWith("&#x3c;")) return couldBeRawPrefix("<" + lower.substring(6));
        return couldBeRawPrefix(lower);
    }

    private static boolean couldBeRawPrefix(String value) {
        if (!value.startsWith("<")) return false;
        int index = 1;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        if (index == value.length()) return true;
        if (value.charAt(index) == '/') {
            index++;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index == value.length()) return true;
        }
        int wordStart = index;
        while (index < value.length()
                && !Character.isWhitespace(value.charAt(index))
                && value.charAt(index) != '>'
                && value.charAt(index) != '&'
                && value.charAt(index) != '/'
                && value.charAt(index) != '\\') {
            index++;
        }
        String word = value.substring(wordStart, index);
        if (word.isEmpty()) return true;
        if ("think".startsWith(word) || "thinking".startsWith(word)) return true;
        return "think".equals(word) || "thinking".equals(word);
    }

    public static record TagMatch(int index, int end, boolean closing, boolean selfClosing) {
    }
}
