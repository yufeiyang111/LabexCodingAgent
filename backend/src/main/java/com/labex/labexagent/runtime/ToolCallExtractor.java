package com.labex.labexagent.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider 文本工具调用的严格兼容解析器。
 *
 * <p>原生 tool call 始终优先；本类只接受显式、完整且唯一的兼容信封，绝不从普通正文猜测工具名。</p>
 */
public final class ToolCallExtractor {
    private static final int MAX_ENVELOPE_CHARS = 256 * 1024;
    private static final Pattern TOOL_CALL_OPEN = Pattern.compile("(?is)<\\s*(?:minimax:)?tool_call\\s*>");
    private static final Pattern TOOL_CALL_ENVELOPE = Pattern.compile(
            "(?is)<\\s*(?:minimax:)?tool_call\\s*>(.*?)</\\s*(?:minimax:)?tool_call\\s*>");
    private static final Pattern INVOKE_OPEN = Pattern.compile("(?is)<\\s*(?:minimax:)?invoke\\b[^>]*>");
    private static final Pattern INVOKE_ENVELOPE = Pattern.compile(
            "(?is)<\\s*(?:minimax:)?invoke\\b([^>]*)>(.*?)</\\s*(?:minimax:)?invoke\\s*>");
    private static final Pattern FENCE_OPEN = Pattern.compile("(?is)```\\s*tool_call(?:\\s*\\r?\\n|\\s+)");
    private static final Pattern FENCE_ENVELOPE = Pattern.compile(
            "(?is)```\\s*tool_call(?:\\s*\\r?\\n|\\s+)(.*?)```");
    private static final Pattern NAME_PREFIX = Pattern.compile(
            "(?is)^([a-zA-Z_][a-zA-Z0-9_.:-]*)\\s*(\\{.*})$");
    private static final Pattern DOUBLE_QUOTED_NAME = Pattern.compile("(?is)\\bname\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SINGLE_QUOTED_NAME = Pattern.compile("(?is)\\bname\\s*=\\s*'([^']+)'");
    private static final Pattern PARAMETER_OPEN = Pattern.compile("(?is)<\\s*parameter\\b[^>]*>");
    private static final Pattern PARAMETER = Pattern.compile(
            "(?is)<\\s*parameter\\b([^>]*)>(.*?)</\\s*parameter\\s*>");
    private static final Set<String> JSON_ENVELOPE_FIELDS = Set.of("name", "tool", "arguments", "args", "id");

    private ToolCallExtractor() {
    }

    public static Extraction extract(String content) {
        if (content == null || content.isBlank()) {
            return Extraction.none();
        }

        int openingCount = count(TOOL_CALL_OPEN, content)
                + count(INVOKE_OPEN, content)
                + count(FENCE_OPEN, content);
        if (openingCount == 0) {
            return Extraction.none();
        }
        if (openingCount > 1) {
            return Extraction.rejected(Status.AMBIGUOUS, "multiple explicit tool-call envelopes were found");
        }

        List<Candidate> candidates = new ArrayList<>();
        collect(TOOL_CALL_ENVELOPE, content, "tool_call", candidates);
        collect(INVOKE_ENVELOPE, content, "invoke", candidates);
        collect(FENCE_ENVELOPE, content, "tool_call_fence", candidates);
        if (candidates.isEmpty()) {
            return Extraction.rejected(Status.INCOMPLETE, "explicit tool-call envelope is not closed");
        }
        if (candidates.size() != 1) {
            return Extraction.rejected(Status.AMBIGUOUS, "multiple complete tool-call envelopes were found");
        }

        Candidate candidate = candidates.get(0);
        if (!content.substring(0, candidate.start()).isBlank()
                || !content.substring(candidate.end()).isBlank()) {
            return Extraction.rejected(Status.INVALID,
                    "explicit tool-call envelope must be the entire model output");
        }
        if (candidate.body().length() > MAX_ENVELOPE_CHARS) {
            return Extraction.rejected(Status.INVALID, "tool-call envelope exceeds the compatibility size limit");
        }
        try {
            return switch (candidate.format()) {
                case "invoke" -> parseInvoke(candidate);
                case "tool_call", "tool_call_fence" -> parseJsonOrNamedEnvelope(candidate);
                default -> Extraction.rejected(Status.INVALID, "unsupported explicit tool-call envelope");
            };
        } catch (IncompleteEnvelopeException incomplete) {
            return Extraction.rejected(Status.INCOMPLETE, incomplete.getMessage());
        } catch (IllegalArgumentException invalid) {
            return Extraction.rejected(Status.INVALID, invalid.getMessage());
        }
    }

    private static Extraction parseJsonOrNamedEnvelope(Candidate candidate) {
        String body = candidate.body().trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("tool-call envelope payload is empty");
        }
        JsonObject envelope;
        if (body.startsWith("{")) {
            envelope = parseObject(body, "tool-call envelope JSON is invalid");
            rejectUnknownEnvelopeFields(envelope);
            String name = oneString(envelope, "name", "tool");
            JsonObject arguments = oneObject(envelope, "arguments", "args");
            return Extraction.valid(name, arguments, candidate.format());
        }

        Matcher named = NAME_PREFIX.matcher(body);
        if (!named.matches()) {
            throw new IllegalArgumentException("tool-call envelope must contain a JSON object with name and arguments");
        }
        String name = requireToolName(named.group(1));
        JsonObject arguments = parseObject(named.group(2), "tool-call arguments JSON is invalid");
        return Extraction.valid(name, arguments, candidate.format());
    }

    private static Extraction parseInvoke(Candidate candidate) {
        String name = attribute(candidate.attributes(), DOUBLE_QUOTED_NAME, SINGLE_QUOTED_NAME);
        name = requireToolName(name);
        String body = candidate.body();
        int parameterOpenings = count(PARAMETER_OPEN, body);
        List<Parameter> parameters = new ArrayList<>();
        Matcher matcher = PARAMETER.matcher(body);
        while (matcher.find()) {
            String parameterName = attribute(matcher.group(1), DOUBLE_QUOTED_NAME, SINGLE_QUOTED_NAME);
            if (parameterName == null || parameterName.isBlank()) {
                throw new IllegalArgumentException("invoke parameter is missing a name attribute");
            }
            parameters.add(new Parameter(parameterName.trim(), matcher.group(2), matcher.start(), matcher.end()));
        }
        if (parameterOpenings != parameters.size()) {
            throw new IncompleteEnvelopeException("invoke parameter envelope is not closed");
        }
        String remaining = removeRanges(body, parameters).trim();
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("invoke envelope contains unsupported content outside parameters");
        }

        JsonObject arguments = new JsonObject();
        Set<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!names.add(parameter.name())) {
                throw new IllegalArgumentException("invoke envelope contains duplicate parameter names");
            }
            arguments.add(parameter.name(), parseParameterValue(parameter.value()));
        }
        return Extraction.valid(name, arguments, candidate.format());
    }

    private static JsonElement parseParameterValue(String rawValue) {
        String value = decodeXml(rawValue == null ? "" : rawValue.trim());
        if (value.isEmpty()) {
            return new com.google.gson.JsonPrimitive("");
        }
        if (value.startsWith("{") || value.startsWith("[")
                || (value.startsWith("\"") && value.endsWith("\""))
                || "true".equals(value) || "false".equals(value) || "null".equals(value)
                || value.matches("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")) {
            try {
                return JsonParser.parseString(value);
            } catch (RuntimeException invalidJsonLiteral) {
                throw new IllegalArgumentException("invoke parameter JSON value is invalid");
            }
        }
        return new com.google.gson.JsonPrimitive(value);
    }

    private static String removeRanges(String body, List<Parameter> parameters) {
        if (parameters.isEmpty()) {
            return body;
        }
        StringBuilder remaining = new StringBuilder(body);
        parameters.stream().sorted(Comparator.comparingInt(Parameter::start).reversed())
                .forEach(parameter -> remaining.delete(parameter.start(), parameter.end()));
        return remaining.toString();
    }

    private static String oneString(JsonObject envelope, String first, String second) {
        boolean hasFirst = envelope.has(first);
        boolean hasSecond = envelope.has(second);
        if (hasFirst == hasSecond) {
            throw new IllegalArgumentException("tool-call envelope must contain exactly one tool name field");
        }
        JsonElement value = hasFirst ? envelope.get(first) : envelope.get(second);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("tool-call name must be a string");
        }
        return requireToolName(value.getAsString());
    }

    private static JsonObject oneObject(JsonObject envelope, String first, String second) {
        boolean hasFirst = envelope.has(first);
        boolean hasSecond = envelope.has(second);
        if (hasFirst && hasSecond) {
            throw new IllegalArgumentException("tool-call envelope contains multiple arguments fields");
        }
        if (!hasFirst && !hasSecond) {
            return new JsonObject();
        }
        JsonElement value = hasFirst ? envelope.get(first) : envelope.get(second);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("tool-call arguments must be a JSON object");
        }
        return value.getAsJsonObject().deepCopy();
    }

    private static void rejectUnknownEnvelopeFields(JsonObject envelope) {
        for (String key : envelope.keySet()) {
            if (!JSON_ENVELOPE_FIELDS.contains(key)) {
                throw new IllegalArgumentException("tool-call envelope contains an unknown field");
            }
        }
    }

    private static JsonObject parseObject(String json, String failureMessage) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("tool-call payload must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (IllegalArgumentException known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(failureMessage);
        }
    }

    private static String requireToolName(String value) {
        if (value == null || !value.matches("[a-zA-Z_][a-zA-Z0-9_.:-]*")) {
            throw new IllegalArgumentException("tool-call name is missing or invalid");
        }
        return value;
    }

    @SafeVarargs
    private static String attribute(String attributes, Pattern... patterns) {
        if (attributes == null) {
            return null;
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(attributes);
            if (matcher.find()) {
                return decodeXml(matcher.group(1));
            }
        }
        return null;
    }

    private static String decodeXml(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static void collect(Pattern pattern, String content, String format, List<Candidate> candidates) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String attributes = "invoke".equals(format) ? matcher.group(1) : "";
            String body = "invoke".equals(format) ? matcher.group(2) : matcher.group(1);
            candidates.add(new Candidate(format, attributes, body, matcher.start(), matcher.end()));
        }
    }

    private static int count(Pattern pattern, String content) {
        int count = 0;
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public enum Status {
        NONE,
        VALID,
        INCOMPLETE,
        AMBIGUOUS,
        INVALID
    }

    public record Extraction(Status status, String toolName, JsonObject arguments, String format, String reason) {
        static Extraction none() {
            return new Extraction(Status.NONE, "", new JsonObject(), "", "");
        }

        static Extraction valid(String toolName, JsonObject arguments, String format) {
            return new Extraction(Status.VALID, toolName,
                    arguments == null ? new JsonObject() : arguments.deepCopy(), format, "");
        }

        static Extraction rejected(Status status, String reason) {
            return new Extraction(status, "", new JsonObject(), "", reason == null ? "" : reason);
        }

        public boolean executable() {
            return status == Status.VALID;
        }
    }

    private record Candidate(String format, String attributes, String body, int start, int end) {
    }

    private record Parameter(String name, String value, int start, int end) {
    }

    private static final class IncompleteEnvelopeException extends IllegalArgumentException {
        private IncompleteEnvelopeException(String message) {
            super(message);
        }
    }
}
