package com.labex.rag.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.rag.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * MiniMax LLM Service
 */
@Slf4j
@Service
public class MiniMaxChat implements LLMChat {

    private final RagConfig ragConfig;
    private final RestTemplate restTemplate;

    public MiniMaxChat(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(300000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Send chat request to MiniMax API
     *
     * @param prompt     System prompt
     * @param context    Retrieved context
     * @param question  User question
     * @return Generated answer
     */
    public String chat(String prompt, String context, String question) {
        try {
            String apiKey = ragConfig.getMiniMaxApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return "LLM API key not configured. Please set rag.mini-max-api-key in application.yml";
            }

            String baseUrl = ragConfig.getMiniMaxBaseUrl().replaceAll("/$", "");
            String url = baseUrl + "/text/chatcompletion_v2";

            log.info("MiniMax API URL: {}", url);

            // Build messages in Anthropic format
            String fullSystemPrompt = prompt + "\n\nContext:\n" + context;

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", fullSystemPrompt + "\n\n用户问题: " + question));

            // Request body using Anthropic-style format
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", ragConfig.getMiniMaxModel());
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 8192);
            requestBody.put("user_id", "labex-system");

            log.info("MiniMax Request body: {}", requestBody);

            // Send request with Authorization header
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            org.springframework.http.HttpEntity<Map<String, Object>> entity =
                    new org.springframework.http.HttpEntity<>(requestBody, headers);

            log.info("Sending request to MiniMax API...");
            org.springframework.http.ResponseEntity<Map> response =
                    restTemplate.postForEntity(url, entity, Map.class);

            log.info("MiniMax Response status: {}", response.getStatusCode());
            log.info("MiniMax Response body: {}", response.getBody());

            if (response.getStatusCode().value() == 200 && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();

                log.info("MiniMax Response body keys: {}", body.keySet());

                // Check for content in response
                if (body.containsKey("choices")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> choice = choices.get(0);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choice.get("message");
                        if (message != null && message.get("content") != null) {
                            return message.get("content").toString();
                        }
                    }
                }

                // Check base_resp for errors
                if (body.containsKey("base_resp")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> baseResp = (Map<String, Object>) body.get("base_resp");
                    if (baseResp != null) {
                        log.error("MiniMax API error: {}", baseResp);
                        return "LLM error: " + baseResp.get("status_msg");
                    }
                }

                // Fallback
                return body.getOrDefault("answer", "Failed to generate response").toString();
            }

            return "LLM request failed with status: " + response.getStatusCode();
        } catch (Exception e) {
            log.error("MiniMax chat error: {}", e.getMessage());
            log.error("MiniMax chat error type: {}", e.getClass().getName());
            if (e.getCause() != null) {
                log.error("Cause: {}", e.getCause().getMessage());
            }
            return "LLM error: " + e.getMessage();
        }
    }

    public String chatStream(String prompt, String context, String question) {
        return chat(prompt, context, question);
    }

    public void chatStream(String prompt, String context, String question, Consumer<StreamChunk> onChunk) {
        chatStream(prompt, context, question, true, onChunk);
    }

    public void chatStream(String prompt, String context, String question, boolean enableThinking, Consumer<StreamChunk> onChunk) {
        HttpURLConnection connection = null;
        try {
            String apiKey = ragConfig.getMiniMaxApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                onChunk.accept(StreamChunk.error("LLM API key not configured. Please set rag.mini-max-api-key in application.yml"));
                return;
            }

            String url = ragConfig.getMiniMaxBaseUrl().replaceAll("/$", "") + "/text/chatcompletion_v2";
            String fullSystemPrompt = prompt + "\n\nContext:\n" + context;

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", fullSystemPrompt + "\n\n用户问题: " + question));

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", ragConfig.getMiniMaxModel());
            requestBody.put("messages", messages);
            requestBody.put("stream", true);
            requestBody.put("enable_thinking", enableThinking);
            requestBody.put("max_tokens", 8192);
            requestBody.put("max_completion_tokens", 8192);
            requestBody.put("user_id", "labex-system");

            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(300000);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(new com.google.gson.Gson().toJson(requestBody).getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            if (status != 200) {
                String errorBody = readAll(connection.getErrorStream());
                onChunk.accept(StreamChunk.error("MiniMax stream API error " + status + ": " + errorBody));
                return;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean[] emittedDelta = {false};
                String[] lastMessageContent = {""};
                ThinkTagStreamParser thinkParser = new ThinkTagStreamParser(onChunk);
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        thinkParser.flush();
                        onChunk.accept(StreamChunk.completed());
                        break;
                    }
                    parseStreamData(data, onChunk, thinkParser, emittedDelta, lastMessageContent);
                }
                thinkParser.flush();
            }
        } catch (Exception e) {
            log.error("MiniMax stream error: {}", e.getMessage(), e);
            onChunk.accept(StreamChunk.error("LLM stream error: " + e.getMessage()));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 流式解析 <think>...</think> 标签的状态机。
     * 处理标签可能跨多个 chunk 的情况。
     */
    private static class ThinkTagStreamParser {
        private static final String OPEN_TAG = "<think>";
        private static final String CLOSE_TAG = "</think>";
        private final Consumer<StreamChunk> onChunk;
        private final StringBuilder buffer = new StringBuilder();
        private boolean inThink = false;

        ThinkTagStreamParser(Consumer<StreamChunk> onChunk) {
            this.onChunk = onChunk;
        }

        /** 推入一段文本，自动分离思考内容和正文 */
        void push(String text) {
            if (text == null || text.isEmpty()) return;
            buffer.append(text);
            process();
        }

        /** 流结束时刷新剩余 buffer */
        void flush() {
            if (buffer.length() > 0) {
                emit(buffer.toString(), inThink);
                buffer.setLength(0);
            }
        }

        private void emit(String text, boolean thinking) {
            if (text.isEmpty()) return;
            if (thinking) {
                onChunk.accept(StreamChunk.thinking(text));
            } else {
                onChunk.accept(StreamChunk.text(text));
            }
        }

        private void process() {
            while (buffer.length() > 0) {
                if (!inThink) {
                    int openIdx = buffer.indexOf(OPEN_TAG);
                    if (openIdx >= 0) {
                        // <think> 前的正文
                        if (openIdx > 0) {
                            emit(buffer.substring(0, openIdx), false);
                        }
                        buffer.delete(0, openIdx + OPEN_TAG.length());
                        inThink = true;
                        continue;
                    }
                    // buffer 尾部可能是 <think> 的前缀，保留等待更多数据
                    int safe = findSafeLength(buffer, OPEN_TAG);
                    if (safe > 0) {
                        emit(buffer.substring(0, safe), false);
                        buffer.delete(0, safe);
                    }
                    break;
                } else {
                    int closeIdx = buffer.indexOf(CLOSE_TAG);
                    if (closeIdx >= 0) {
                        // <think> 内的思考内容
                        if (closeIdx > 0) {
                            emit(buffer.substring(0, closeIdx), true);
                        }
                        buffer.delete(0, closeIdx + CLOSE_TAG.length());
                        inThink = false;
                        continue;
                    }
                    // buffer 尾部可能是 </think> 的前缀，保留等待更多数据
                    int safe = findSafeLength(buffer, CLOSE_TAG);
                    if (safe > 0) {
                        emit(buffer.substring(0, safe), true);
                        buffer.delete(0, safe);
                    }
                    break;
                }
            }
        }

        /** 找到可以安全输出的长度（尾部可能是 tag 前缀的部分保留） */
        private static int findSafeLength(StringBuilder buf, String tag) {
            int maxPrefix = tag.length() - 1;
            if (maxPrefix <= 0) return buf.length();
            for (int len = Math.min(maxPrefix, buf.length()); len > 0; len--) {
                if (tag.startsWith(buf.substring(buf.length() - len))) {
                    return buf.length() - len;
                }
            }
            return buf.length();
        }
    }

    private void parseStreamData(
            String data,
            Consumer<StreamChunk> onChunk,
            ThinkTagStreamParser thinkParser,
            boolean[] emittedDelta,
            String[] lastMessageContent) {
        try {
            JsonObject root = JsonParser.parseString(data).getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) {
                return;
            }
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                    ? choice.getAsJsonObject("delta")
                    : null;

            // 优先检查 reasoning_content（DeepSeek 等模型原生支持）
            if (delta != null && delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                String reasoning = delta.get("reasoning_content").getAsString();
                if (!reasoning.isEmpty()) {
                    emittedDelta[0] = true;
                    onChunk.accept(StreamChunk.thinking(reasoning));
                }
            }

            // 正文 content（可能包含 <think> 标签，通过 parser 分离）
            if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                String content = delta.get("content").getAsString();
                if (!content.isEmpty()) {
                    emittedDelta[0] = true;
                    thinkParser.push(content);
                }
            }

            if (emittedDelta[0]) {
                return;
            }
            JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                    ? choice.getAsJsonObject("message")
                    : null;
            if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
                String content = message.get("content").getAsString();
                String previous = lastMessageContent[0] == null ? "" : lastMessageContent[0];
                String deltaContent = content.startsWith(previous) ? content.substring(previous.length()) : content;
                lastMessageContent[0] = content;
                if (!deltaContent.isEmpty()) {
                    thinkParser.push(deltaContent);
                }
            }
        } catch (Exception parseError) {
            log.debug("MiniMax stream chunk parse skipped: {}", parseError.getMessage());
        }
    }

    private String readAll(java.io.InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public record StreamChunk(String type, String content, boolean finished) {
        public static StreamChunk text(String content) {
            return new StreamChunk("text_delta", content, false);
        }

        public static StreamChunk thinking(String content) {
            return new StreamChunk("thinking_delta", content, false);
        }

        public static StreamChunk completed() {
            return new StreamChunk("done", "", true);
        }

        public static StreamChunk error(String content) {
            return new StreamChunk("error", content, true);
        }
    }

    public Map<String, Object> chatWithTools(String sysPrompt, ArrayList<Map<String, Object>> msgs, List<Map<String, Object>> tools) {
        try {
            String apiKey = ragConfig.getMiniMaxApiKey();
            if (apiKey == null || apiKey.isBlank()) return Map.of("type", "final", "content", "API key not configured");
            String url = ragConfig.getMiniMaxBaseUrl().replaceAll("/$", "") + "/text/chatcompletion_v2";
            ArrayList<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", sysPrompt));
            messages.addAll(msgs);
            Map<String, Object> body = new HashMap<>();
            body.put("model", ragConfig.getMiniMaxModel());
            body.put("messages", messages);
            body.put("max_tokens", 8192);
            if (tools != null && !tools.isEmpty()) { body.put("tools", tools); body.put("tool_choice", "auto"); }
            org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
            h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            h.set("Authorization", "Bearer " + apiKey);
            org.springframework.http.HttpEntity<Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(body, h);
            org.springframework.http.ResponseEntity<Map> r = restTemplate.postForEntity(url, entity, Map.class);
            if (r.getStatusCode().value() == 200 && r.getBody() != null) {
                Map<String, Object> rb = r.getBody();
                if (rb.containsKey("choices")) {
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) rb.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                        if (msg != null) {
                            String content = msg.get("content") != null ? msg.get("content").toString() : "";
                            if (msg.containsKey("tool_calls")) {
                                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                                if (tcs != null && !tcs.isEmpty()) {
                                    Map<String, Object> fn = (Map<String, Object>) tcs.get(0).get("function");
                                    return Map.of("type", "tool_call", "tool", fn.get("name").toString(), "arguments", fn.get("arguments").toString(), "thinking", content);
                                }
                            }
                            return Map.of("type", "final", "content", content.isEmpty() ? "No response" : content);
                        }
                    }
                }
            }
            return Map.of("type", "final", "content", "API call failed");
        } catch (Exception e) { log.error("MiniMax tools: {}", e.getMessage()); return Map.of("type", "final", "content", "LLM error: " + e.getMessage()); }
    }
}
