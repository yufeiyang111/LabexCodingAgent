package com.labex.labexagent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final Gson GSON = new Gson();

    @Override
    public String getProviderId() { return "openai_compatible"; }

    @Override
    public String getProviderName() { return "OpenAI Compatible"; }

    @Override
    public boolean supportsStreaming() { return true; }

    @Override
    public boolean supportsToolCalling() { return true; }

    @Override
    public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                              List<Map<String, Object>> tools, LlmConfig config) {
        try {
            String body = buildRequestBody(sysPrompt, msgs, tools, config, false);
            String url = normalizeUrl(config.baseUrl()) + "/v1/chat/completions";
            String response = httpPost(url, body, config.apiKey());
            return parseResponse(response);
        } catch (Exception e) {
            log.error("LLM chat error: {}", e.getMessage());
            return Map.of("type", "error", "message", "LLM error: " + e.getMessage(), "content", "");
        }
    }

    @Override
    public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                            List<Map<String, Object>> tools, LlmConfig config,
                            Consumer<StreamChunk> onChunk) {
        HttpURLConnection conn = null;
        try {
            String body = buildRequestBody(sysPrompt, msgs, tools, config, true);
            String url = normalizeUrl(config.baseUrl()) + "/v1/chat/completions";

            java.net.URL uri = URI.create(url).toURL();
            conn = (HttpURLConnection) uri.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey());
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(300000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String errBody = readAll(conn.getErrorStream());
                onChunk.accept(new StreamChunk("error", "API error " + code + ": " + errBody, null, null, null, true));
                return;
            }

            StringBuilder contentBuf = new StringBuilder();
            StringBuilder thinkingBuf = new StringBuilder();
            String currentToolName = null;
            StringBuilder currentToolArgs = null;

            // <think> 标签解析器（处理模型在 content 中返回思考内容的情况）
            ThinkTagStreamParser thinkParser = new ThinkTagStreamParser(
                text -> {
                    thinkingBuf.append(text);
                    onChunk.accept(new StreamChunk("thinking_delta", text, null, null, thinkingBuf.toString(), false));
                },
                text -> {
                    contentBuf.append(text);
                    onChunk.accept(new StreamChunk("text_delta", text, null, null, null, false));
                }
            );

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) {
                        thinkParser.flush();
                        onChunk.accept(new StreamChunk("done", contentBuf.toString(), currentToolName,
                                currentToolArgs != null ? currentToolArgs.toString() : null,
                                thinkingBuf.toString(), true));
                        break;
                    }
                    try {
                        JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                        var choices = chunk.getAsJsonArray("choices");
                        if (choices == null || choices.isEmpty()) continue;
                        var delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                        if (delta == null) continue;

                        if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                            var toolCalls = delta.getAsJsonArray("tool_calls");
                            for (var tc : toolCalls) {
                                var fn = tc.getAsJsonObject().getAsJsonObject("function");
                                if (fn != null) {
                                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                                        currentToolName = fn.get("name").getAsString();
                                        currentToolArgs = new StringBuilder();
                                    }
                                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                        if (currentToolArgs == null) currentToolArgs = new StringBuilder();
                                        currentToolArgs.append(fn.get("arguments").getAsString());
                                        onChunk.accept(new StreamChunk("tool_args_delta", fn.get("arguments").getAsString(),
                                                currentToolName, currentToolArgs.toString(), null, false));
                                    }
                                }
                            }
                        }

                        // 优先检查 reasoning_content（DeepSeek 等模型原生支持）
                        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                            String thinking = delta.get("reasoning_content").getAsString();
                            thinkingBuf.append(thinking);
                            onChunk.accept(new StreamChunk("thinking_delta", thinking, null, null, thinkingBuf.toString(), false));
                        }

                        // content 可能包含 <think> 标签，通过 parser 分离
                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                            String content = delta.get("content").getAsString();
                            thinkParser.push(content);
                        }
                    } catch (Exception parseEx) {
                        log.debug("SSE parse skip: {}", parseEx.getMessage());
                    }
                }
                thinkParser.flush();
            }

            if (currentToolName != null && currentToolArgs != null) {
                onChunk.accept(new StreamChunk("tool_call", "", currentToolName, currentToolArgs.toString(),
                        thinkingBuf.toString(), true));
            }

        } catch (Exception e) {
            log.error("LLM stream error: {}", e.getMessage());
            try {
                onChunk.accept(new StreamChunk("error", "LLM stream error: " + e.getMessage(), null, null, null, true));
            } catch (Exception callbackEx) {
                log.debug("Failed to send error chunk to callback: {}", callbackEx.getMessage());
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String buildRequestBody(String sysPrompt, List<Map<String, Object>> msgs,
                                     List<Map<String, Object>> tools, LlmConfig config, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.modelName());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", sysPrompt));
        messages.addAll(msgs);
        body.put("messages", messages);

        body.put("max_tokens", config.maxTokens() != null ? config.maxTokens() : 8192);
        if (config.temperature() != null) body.put("temperature", config.temperature());
        if (stream) body.put("stream", true);
        // 启用模型思考模式（DeepSeek/MiniMax 等模型支持 reasoning_content）
        body.put("enable_thinking", true);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        return GSON.toJson(body);
    }

    private Map<String, Object> parseResponse(String response) {
        try {
            JsonObject rb = JsonParser.parseString(response).getAsJsonObject();

            Map<String, Object> usage = extractUsage(rb);

            var choices = rb.getAsJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                var msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null) {
                    String content = msg.has("content") && !msg.get("content").isJsonNull()
                            ? msg.get("content").getAsString() : "";

                    String thinking = "";
                    if (msg.has("reasoning_content") && !msg.get("reasoning_content").isJsonNull()) {
                        thinking = msg.get("reasoning_content").getAsString();
                    }

                    if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()) {
                        var tcs = msg.getAsJsonArray("tool_calls");
                        if (tcs != null && !tcs.isEmpty()) {
                            var fn = tcs.get(0).getAsJsonObject().getAsJsonObject("function");
                            if (fn != null) {
                                Map<String, Object> result = new HashMap<>();
                                result.put("type", "tool_call");
                                result.put("tool", fn.get("name").getAsString());
                                result.put("arguments", fn.get("arguments").getAsString());
                                result.put("thinking", thinking.isEmpty() ? content : thinking);
                                if (usage != null) result.put("usage", usage);
                                return result;
                            }
                        }
                    }

                    if (!content.isEmpty()) {
                        Map<String, Object> result = new HashMap<>();
                        result.put("type", "text");
                        result.put("content", content);
                        result.put("thinking", thinking);
                        if (usage != null) result.put("usage", usage);
                        return result;
                    }
                    if (!thinking.isEmpty()) {
                        Map<String, Object> result = new HashMap<>();
                        result.put("type", "text");
                        result.put("content", thinking);
                        if (usage != null) result.put("usage", usage);
                        return result;
                    }
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("type", "error");
            result.put("message", "Empty response from API");
            result.put("content", "");
            if (usage != null) result.put("usage", usage);
            return result;
        } catch (Exception e) {
            return Map.of("type", "error", "message", "Parse error: " + e.getMessage(), "content", "");
        }
    }

    private Map<String, Object> extractUsage(JsonObject rb) {
        try {
            if (!rb.has("usage") || rb.get("usage").isJsonNull()) return null;
            JsonObject usage = rb.getAsJsonObject("usage");
            Map<String, Object> result = new HashMap<>();
            result.put("prompt_tokens", usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsInt() : 0);
            result.put("completion_tokens", usage.has("completion_tokens") ? usage.get("completion_tokens").getAsInt() : 0);
            result.put("total_tokens", usage.has("total_tokens") ? usage.get("total_tokens").getAsInt() : 0);
            int cached = nestedInt(usage, "prompt_tokens_details", "cached_tokens");
            if (cached == 0) cached = nestedInt(usage, "input_token_details", "cache_read");
            if (cached == 0) cached = intValue(usage, "cached_tokens");
            int cacheWrite = nestedInt(usage, "input_token_details", "cache_creation");
            if (cacheWrite == 0) cacheWrite = intValue(usage, "cache_creation_input_tokens");
            result.put("cached_tokens", cached);
            result.put("cache_write_tokens", cacheWrite);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private int nestedInt(JsonObject root, String objectName, String fieldName) {
        try {
            if (!root.has(objectName) || root.get(objectName).isJsonNull()) return 0;
            JsonObject nested = root.getAsJsonObject(objectName);
            return intValue(nested, fieldName);
        } catch (Exception e) {
            return 0;
        }
    }

    private int intValue(JsonObject root, String fieldName) {
        try {
            return root.has(fieldName) && !root.get(fieldName).isJsonNull() ? root.get(fieldName).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String httpPost(String url, String body, String apiKey) throws Exception {
        java.net.URL uri = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) uri.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(300000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readAll(conn.getErrorStream());
            conn.disconnect();
            throw new RuntimeException("API error " + code + ": " + err);
        }

        String resp = readAll(conn.getInputStream());
        conn.disconnect();
        return resp;
    }

    private String readAll(java.io.InputStream is) {
        if (is == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private String normalizeUrl(String url) {
        if (url == null) return "https://api.openai.com";
        url = url.replaceAll("/+$", "");
        if (url.endsWith("/v1")) url = url.substring(0, url.length() - 3);
        return url;
    }

    /**
     * 流式解析 <think>...</think> 标签的状态机
     * 处理标签跨多个 chunk 的情况
     */
    private static class ThinkTagStreamParser {
        private static final String OPEN_TAG = "<think>";
        private static final String CLOSE_TAG = "</think>";
        private final java.util.function.Consumer<String> onThinking;
        private final java.util.function.Consumer<String> onText;
        private final StringBuilder buffer = new StringBuilder();
        private boolean inThink = false;

        ThinkTagStreamParser(java.util.function.Consumer<String> onThinking, java.util.function.Consumer<String> onText) {
            this.onThinking = onThinking;
            this.onText = onText;
        }

        void push(String text) {
            if (text == null || text.isEmpty()) return;
            buffer.append(text);
            process();
        }

        void flush() {
            if (buffer.length() > 0) {
                emit(buffer.toString(), inThink);
                buffer.setLength(0);
            }
        }

        private void emit(String text, boolean thinking) {
            if (text.isEmpty()) return;
            if (thinking) onThinking.accept(text);
            else onText.accept(text);
        }

        private void process() {
            while (buffer.length() > 0) {
                if (!inThink) {
                    int openIdx = buffer.indexOf(OPEN_TAG);
                    if (openIdx >= 0) {
                        if (openIdx > 0) emit(buffer.substring(0, openIdx), false);
                        buffer.delete(0, openIdx + OPEN_TAG.length());
                        inThink = true;
                        continue;
                    }
                    int safe = findSafeLength(buffer, OPEN_TAG);
                    if (safe > 0) { emit(buffer.substring(0, safe), false); buffer.delete(0, safe); }
                    break;
                } else {
                    int closeIdx = buffer.indexOf(CLOSE_TAG);
                    if (closeIdx >= 0) {
                        if (closeIdx > 0) emit(buffer.substring(0, closeIdx), true);
                        buffer.delete(0, closeIdx + CLOSE_TAG.length());
                        inThink = false;
                        continue;
                    }
                    int safe = findSafeLength(buffer, CLOSE_TAG);
                    if (safe > 0) { emit(buffer.substring(0, safe), true); buffer.delete(0, safe); }
                    break;
                }
            }
        }

        private static int findSafeLength(StringBuilder buf, String tag) {
            int maxPrefix = tag.length() - 1;
            if (maxPrefix <= 0) return buf.length();
            for (int len = Math.min(maxPrefix, buf.length()); len > 0; len--) {
                if (tag.startsWith(buf.substring(buf.length() - len))) return buf.length() - len;
            }
            return buf.length();
        }
    }
}
