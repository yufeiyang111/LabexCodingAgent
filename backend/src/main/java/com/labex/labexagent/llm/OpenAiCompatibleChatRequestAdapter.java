package com.labex.labexagent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * OpenAI-compatible Chat 的唯一请求构造 Adapter。
 *
 * <p>该模块只负责把已解析的 LLM 配置转换成 HTTP 请求，不负责发送请求、解析响应或维护
 * Agent transcript。默认配置保持旧路径的 reasoning_effort=medium 行为；高级字段只有在用户
 * 明确配置路径和值后才会写入请求。
 */
public class OpenAiCompatibleChatRequestAdapter {
    private static final Gson GSON = new Gson();
    private static final List<String> DEFAULT_REASONING_LEVELS = List.of("low", "medium", "high", "xhigh");
    private static final Set<String> PROTECTED_BODY_FIELDS = Set.of("model", "messages", "tools", "stream");
    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "authorization", "x-api-key", "host", "content-type", "content-length",
            "transfer-encoding", "connection");

    public PreparedRequest adapt(String sysPrompt, List<Map<String, Object>> msgs,
                                 List<Map<String, Object>> tools, LlmProvider.LlmConfig config,
                                 boolean stream, boolean includeStreamUsage) {
        if (config == null) throw new IllegalArgumentException("LLM config is required");
        JsonObject options = parseOptions(config.requestOptionsJson());
        JsonObject body = new JsonObject();
        body.addProperty("model", config.modelName());

        List<Map<String, Object>> effectiveMessages = new ArrayList<>();
        effectiveMessages.add(Map.of("role", "system", "content", sysPrompt == null ? "" : sysPrompt));
        if (msgs != null) effectiveMessages.addAll(msgs);
        JsonArray messages = GSON.toJsonTree(effectiveMessages).getAsJsonArray();
        body.add("messages", messages);
        body.addProperty("max_tokens", config.maxTokens() == null
                ? com.labex.service.AgentModelConfigService.DEFAULT_MAX_TOKENS : config.maxTokens());
        if (config.temperature() != null) body.addProperty("temperature", config.temperature());
        if (config.promptCacheKeyEnabled() && config.promptCacheKey() != null
                && !config.promptCacheKey().isBlank()) {
            body.addProperty("prompt_cache_key", config.promptCacheKey());
        }
        if (stream) {
            body.addProperty("stream", true);
            if (includeStreamUsage) {
                JsonObject streamOptions = new JsonObject();
                streamOptions.addProperty("include_usage", true);
                body.add("stream_options", streamOptions);
            }
        }
        List<Map<String, Object>> effectiveTools = tools;
        if (effectiveTools != null && !effectiveTools.isEmpty()) {
            body.add("tools", GSON.toJsonTree(effectiveTools));
            body.addProperty("tool_choice", "auto");
            body.addProperty("parallel_tool_calls", false);
        }

        List<String> appliedPaths = new ArrayList<>();
        applyReasoning(body, options, config, appliedPaths);
        applyThinking(body, options, appliedPaths);
        applyBudget(body, options, config, appliedPaths);

        JsonObject overrides = objectAt(options, "requestOverrides");
        JsonObject bodyOverride = objectAt(overrides, "body");
        rejectProtectedBodyOverrides(bodyOverride, "");
        deepMerge(body, bodyOverride);
        restoreDynamicFields(body, config, messages, tools, stream, includeStreamUsage);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + config.apiKey());
        }
        JsonObject headerOverride = objectAt(overrides, "headers");
        Map<String, String> rejectedHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : headerOverride.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (PROTECTED_HEADERS.contains(lower)) {
                rejectedHeaders.put(name, "protected");
                continue;
            }
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Header override values must be strings: " + name);
            }
            String value = entry.getValue().getAsString();
            if (name.isBlank() || name.contains("\r") || name.contains("\n")
                    || value.contains("\r") || value.contains("\n")) {
                throw new IllegalArgumentException("Header override contains an invalid name or value: " + name);
            }
            headers.put(name, value);
        }
        if (!rejectedHeaders.isEmpty()) {
            throw new IllegalArgumentException("Header override contains protected fields: " + rejectedHeaders.keySet());
        }

        RequestEvidence evidence = new RequestEvidence(
                List.copyOf(appliedPaths),
                List.copyOf(rejectedHeaders.keySet()),
                List.copyOf(jsonPaths(body)),
                config.reasoningEffort() == null ? "medium" : config.reasoningEffort(),
                config.reasoningEffort() == null ? "medium" : config.reasoningEffort(),
                requestShapeDigest(body, messages, effectiveTools),
                digest(canonicalJson(body)),
                messageFingerprints(messages),
                inputShapeChars(messages, effectiveTools),
                effectiveTools == null ? 0 : effectiveTools.size(),
                config.promptCacheKeyRejected());
        if (config.requestEvidenceSink() != null) config.requestEvidenceSink().accept(evidence);
        return new PreparedRequest(GSON.toJson(body), Collections.unmodifiableMap(headers), evidence);
    }

    private void applyReasoning(JsonObject body, JsonObject options, LlmProvider.LlmConfig config,
                                List<String> appliedPaths) {
        JsonObject reasoning = objectAt(options, "reasoning");
        boolean enabled = !config.reasoningEffortDisabled()
                && (!reasoning.has("enabled") || reasoning.get("enabled").isJsonNull()
                || reasoning.get("enabled").getAsBoolean());
        if (!enabled) return;
        String selected = config.reasoningEffort();
        if (selected == null || selected.isBlank() || "none".equalsIgnoreCase(selected)) {
            selected = stringAt(reasoning, "defaultLevel", "medium");
        }
        if ("none".equalsIgnoreCase(selected)) {
            return;
        }
        List<String> allowed = stringListAt(reasoning, "allowedLevels", DEFAULT_REASONING_LEVELS);
        if (!allowed.contains(selected)) {
            throw new IllegalArgumentException("reasoning effort is not allowed: " + selected);
        }
        JsonObject mapping = objectAt(reasoning, "mapping");
        JsonElement value = mapping.has(selected) ? mapping.get(selected).deepCopy() : new com.google.gson.JsonPrimitive(selected);
        String path = stringAt(reasoning, "path", "/reasoning_effort");
        if (path != null && !path.isBlank()) {
            writePointer(body, path, value);
            appliedPaths.add("/reasoning");
            appliedPaths.add("/reasoning/path");
        }
    }

    private void applyThinking(JsonObject body, JsonObject options, List<String> appliedPaths) {
        JsonObject thinking = objectAt(options, "thinking");
        String path = stringAt(thinking, "path", null);
        if (path == null || path.isBlank()) return;
        boolean enabled = booleanAt(thinking, "defaultEnabled", false);
        JsonElement value = thinking.has(enabled ? "onValue" : "offValue")
                ? thinking.get(enabled ? "onValue" : "offValue").deepCopy()
                : new com.google.gson.JsonPrimitive(enabled);
        writePointer(body, path, value);
        appliedPaths.add("/thinking/path");
    }

    private void applyBudget(JsonObject body, JsonObject options, LlmProvider.LlmConfig config,
                             List<String> appliedPaths) {
        JsonObject budget = objectAt(options, "budget");
        if (!booleanAt(budget, "enabled", false)) return;
        String path = stringAt(budget, "path", null);
        if (path == null || path.isBlank()) return;
        String selected = config.reasoningEffort();
        JsonObject mapping = objectAt(budget, "mapping");
        JsonElement value = selected != null && mapping.has(selected)
                ? mapping.get(selected).deepCopy() : budget.get("default");
        if (value == null || value.isJsonNull()) return;
        writePointer(body, path, value.deepCopy());
        appliedPaths.add("/budget/path");
    }

    private void restoreDynamicFields(JsonObject body, LlmProvider.LlmConfig config, JsonArray messages,
                                      List<Map<String, Object>> tools, boolean stream,
                                      boolean includeStreamUsage) {
        body.addProperty("model", config.modelName());
        body.add("messages", messages);
        if (tools == null || tools.isEmpty()) {
            body.remove("tools");
            body.remove("tool_choice");
            body.remove("parallel_tool_calls");
        } else {
            body.add("tools", GSON.toJsonTree(tools));
            body.addProperty("tool_choice", "auto");
            body.addProperty("parallel_tool_calls", false);
        }
        if (stream) {
            body.addProperty("stream", true);
            if (includeStreamUsage) {
                JsonObject streamOptions = new JsonObject();
                streamOptions.addProperty("include_usage", true);
                body.add("stream_options", streamOptions);
            }
        } else {
            body.remove("stream");
            body.remove("stream_options");
        }
    }

    private void rejectProtectedBodyOverrides(JsonObject override, String prefix) {
        for (String key : override.keySet()) {
            if (PROTECTED_BODY_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Body override contains protected field: /" + key);
            }
        }
    }

    private void deepMerge(JsonObject target, JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            JsonElement incoming = entry.getValue();
            JsonElement current = target.get(entry.getKey());
            if (incoming != null && incoming.isJsonObject() && current != null && current.isJsonObject()) {
                deepMerge(current.getAsJsonObject(), incoming.getAsJsonObject());
            } else {
                target.add(entry.getKey(), incoming == null ? JsonNull.INSTANCE : incoming.deepCopy());
            }
        }
    }

    private void writePointer(JsonObject root, String pointer, JsonElement value) {
        if (pointer == null || pointer.isBlank()) return;
        if (!pointer.startsWith("/") || pointer.endsWith("/")) {
            throw new IllegalArgumentException("Invalid JSON Pointer: " + pointer);
        }
        String[] parts = pointer.substring(1).split("/", -1);
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String key = decodePointerPart(parts[i]);
            JsonElement child = current.get(key);
            if (child == null || !child.isJsonObject()) {
                child = new JsonObject();
                current.add(key, child);
            }
            current = child.getAsJsonObject();
        }
        current.add(decodePointerPart(parts[parts.length - 1]), value == null ? JsonNull.INSTANCE : value.deepCopy());
    }

    private String decodePointerPart(String value) {
        return value.replace("~1", "/").replace("~0", "~");
    }

    private JsonObject parseOptions(String json) {
        if (json == null || json.isBlank()) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("request options must be a JSON object");
            return parsed.getAsJsonObject();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("request options JSON is invalid", e);
        }
    }

    private JsonObject objectAt(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) return new JsonObject();
        return object.getAsJsonObject(key);
    }

    private String stringAt(JsonObject object, String key, String fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsString();
        return fallback;
    }

    private boolean booleanAt(JsonObject object, String key, boolean fallback) {
        if (object != null && object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsBoolean();
        return fallback;
    }

    private List<String> stringListAt(JsonObject object, String key, List<String> fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) return fallback;
        List<String> result = new ArrayList<>();
        for (JsonElement item : object.getAsJsonArray(key)) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) result.add(item.getAsString());
        }
        return result.isEmpty() ? fallback : result;
    }

    private List<String> jsonPaths(JsonObject object) {
        return new ArrayList<>(object.keySet());
    }

    public record PreparedRequest(String body, Map<String, String> headers, RequestEvidence evidence) {
    }

    public record RequestEvidence(List<String> appliedConfigPaths, List<String> protectedOverridePaths,
                                  List<String> topLevelBodyPaths, String selectedReasoningLevel,
                                  String resolvedReasoningValue, String requestShapeDigest,
                                  String requestDigest, List<MessageFingerprint> messageFingerprints,
                                  int inputShapeChars, int toolCount,
                                  boolean promptCacheKeyRejected) {
        public RequestEvidence {
            appliedConfigPaths = List.copyOf(appliedConfigPaths == null ? List.of() : appliedConfigPaths);
            protectedOverridePaths = List.copyOf(protectedOverridePaths == null ? List.of() : protectedOverridePaths);
            topLevelBodyPaths = List.copyOf(topLevelBodyPaths == null ? List.of() : topLevelBodyPaths);
            selectedReasoningLevel = selectedReasoningLevel == null ? "" : selectedReasoningLevel;
            resolvedReasoningValue = resolvedReasoningValue == null ? "" : resolvedReasoningValue;
            requestShapeDigest = requestShapeDigest == null ? "" : requestShapeDigest;
            requestDigest = requestDigest == null ? "" : requestDigest;
            messageFingerprints = List.copyOf(messageFingerprints == null ? List.of() : messageFingerprints);
            inputShapeChars = Math.max(0, inputShapeChars);
            toolCount = Math.max(0, toolCount);
        }

        /** 兼容只关心请求选项证据的旧构造方。 */
        public RequestEvidence(List<String> appliedConfigPaths, List<String> protectedOverridePaths,
                               List<String> topLevelBodyPaths, String selectedReasoningLevel,
                               String resolvedReasoningValue) {
            this(appliedConfigPaths, protectedOverridePaths, topLevelBodyPaths, selectedReasoningLevel,
                    resolvedReasoningValue, "", "", List.of(), 0, 0, false);
        }
    }

    public record MessageFingerprint(String digest, int shapeChars) {
        public MessageFingerprint {
            digest = digest == null ? "" : digest;
            shapeChars = Math.max(0, shapeChars);
        }
    }

    private String requestShapeDigest(JsonObject body, JsonArray messages,
                                      List<Map<String, Object>> tools) {
        LinkedHashMap<String, Object> shape = new LinkedHashMap<>();
        shape.put("model", body.has("model") ? body.get("model") : null);
        shape.put("system", messages == null || messages.isEmpty() ? null : messages.get(0));
        shape.put("tools", tools == null ? List.of() : tools);
        return digest(canonicalJson(GSON.toJsonTree(shape)));
    }

    private List<MessageFingerprint> messageFingerprints(JsonArray messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<MessageFingerprint> result = new ArrayList<>(messages.size());
        for (JsonElement message : messages) {
            String canonical = canonicalJson(message);
            result.add(new MessageFingerprint(digest(canonical), canonical.length()));
        }
        return List.copyOf(result);
    }

    private int inputShapeChars(JsonArray messages, List<Map<String, Object>> tools) {
        int total = messageFingerprints(messages).stream()
                .mapToInt(MessageFingerprint::shapeChars).sum();
        return total + canonicalJson(GSON.toJsonTree(tools == null ? List.of() : tools)).length();
    }

    private String canonicalJson(Object value) {
        return canonicalJson(GSON.toJsonTree(value));
    }

    private String canonicalJson(JsonElement value) {
        return GSON.toJson(canonicalElement(stripCacheHints(value)));
    }

    private JsonElement stripCacheHints(JsonElement value) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            value.getAsJsonArray().forEach(item -> array.add(stripCacheHints(item)));
            return array;
        }
        if (!value.isJsonObject()) return value.deepCopy();
        JsonObject object = new JsonObject();
        value.getAsJsonObject().entrySet().stream()
                .filter(entry -> !"cache_control".equals(entry.getKey()))
                .forEach(entry -> object.add(entry.getKey(), stripCacheHints(entry.getValue())));
        return object;
    }

    private JsonElement canonicalElement(JsonElement value) {
        if (value == null || value.isJsonNull() || value.isJsonPrimitive()) return value;
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            value.getAsJsonArray().forEach(item -> array.add(canonicalElement(item)));
            return array;
        }
        JsonObject object = new JsonObject();
        value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> object.add(entry.getKey(), canonicalElement(entry.getValue())));
        return object;
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }
}
