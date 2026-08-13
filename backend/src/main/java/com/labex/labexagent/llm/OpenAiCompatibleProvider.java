package com.labex.labexagent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.runtime.CancellationToken;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenAiCompatibleProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private static final Gson GSON = new Gson();
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final ProviderRetryPolicy retryPolicy;

    public OpenAiCompatibleProvider() {
        this(new OutboundUrlPolicy());
    }

    @Autowired
    public OpenAiCompatibleProvider(OutboundUrlPolicy outboundUrlPolicy) {
        this(outboundUrlPolicy, new ProviderRetryPolicy());
    }

    OpenAiCompatibleProvider(OutboundUrlPolicy outboundUrlPolicy, ProviderRetryPolicy retryPolicy) {
        this.outboundUrlPolicy = Objects.requireNonNull(outboundUrlPolicy, "outboundUrlPolicy");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    @Override
    public String getProviderId() { return "openai_compatible"; }

    @Override
    public String getProviderName() { return "OpenAI Compatible"; }

    @Override
    public boolean supportsStreaming() { return true; }

    @Override
    public boolean supportsToolCalling() { return true; }

    @Override
    public ProviderCapabilities capabilities() { return ProviderCapabilities.OPENAI_COMPATIBLE; }

    @Override
    public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                              List<Map<String, Object>> tools, LlmConfig config) {
        return chatWithTools(sysPrompt, msgs, tools, config, CancellationToken.none());
    }

    @Override
    public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                              List<Map<String, Object>> tools, LlmConfig config,
                                              CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            return cancelledResponse();
        }
        try {
            String body = buildRequestBody(sysPrompt, msgs, tools, config, false);
            String url = buildApiUrl(config.baseUrl(), "/chat/completions");
            String response = httpPost(url, body, config.apiKey(), config, token);
            if (token.isCancellationRequested()) {
                return cancelledResponse();
            }
            return parseResponse(response);
        } catch (CancellationException cancelled) {
            return cancelledResponse();
        } catch (Exception e) {
            if (token.isCancellationRequested()) {
                return cancelledResponse();
            }
            if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()
                    && shouldRetryWithoutReasoningEffortMessage(e.getMessage())) {
                log.info("LLM_CHAT_RETRY_WITHOUT_REASONING_EFFORT model={}", config.modelName());
                return chatWithTools(sysPrompt, msgs, tools, config.withoutReasoningEffort(), token);
            }
            log.error("LLM chat error: {}", e.getMessage());
            return Map.of("type", "error", "message", "LLM error: " + e.getMessage(), "content", "");
        }
    }

    @Override
    public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                           List<Map<String, Object>> tools, LlmConfig config,
                           Consumer<StreamChunk> onChunk) {
        chatStream(sysPrompt, msgs, tools, config, CancellationToken.none(), onChunk);
    }

    @Override
    public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                           List<Map<String, Object>> tools, LlmConfig config,
                           CancellationToken cancellationToken,
                           Consumer<StreamChunk> onChunk) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        boolean includeUsage = capabilities().usage();
        LlmConfig requestConfig = config;
        int retries = 0;
        while (true) {
            if (token.isCancellationRequested()) {
                emitCancelled(onChunk);
                return;
            }
            HttpURLConnection conn = null;
            boolean emittedStreamEvent = false;
            try {
                long requestStartedAt = System.nanoTime();
                String body = buildRequestBody(sysPrompt, msgs, tools, requestConfig, true, includeUsage);
                int requestBytes = body.getBytes(StandardCharsets.UTF_8).length;
                int firstEventTimeoutMs = initialStreamResponseTimeoutMs(config);
                log.info("LLM_STREAM_REQUEST model={} messages={} tools={} requestBytes={} maxTokens={} connectTimeoutMs={} firstEventTimeoutMs={}",
                        config.modelName(), msgs == null ? 0 : msgs.size() + 1, tools == null ? 0 : tools.size(),
                        requestBytes, config.maxTokens(), config.effectiveConnectTimeoutMs(), firstEventTimeoutMs);
                conn = openConnection(buildApiUrl(config.baseUrl(), "/chat/completions"));
                try (CancellationToken.Registration ignored = token.onCancellation(conn::disconnect)) {
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + config.apiKey());
                    conn.setRequestProperty("Accept", "text/event-stream");
                    conn.setRequestProperty("Connection", "keep-alive");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(config.effectiveConnectTimeoutMs());
                    conn.setReadTimeout(firstEventTimeoutMs);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    if (token.isCancellationRequested()) {
                        emitCancelled(onChunk);
                        return;
                    }
                    int code = conn.getResponseCode();
                    long responseHeadersMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt);
                    log.info("LLM_STREAM_HEADERS model={} status={} elapsedMs={}", config.modelName(), code, responseHeadersMs);
                    if (code != 200) {
                        String errBody = readAll(conn.getErrorStream());
                        if (requestConfig.reasoningEffort() != null && !requestConfig.reasoningEffort().isBlank()
                                && shouldRetryWithoutReasoningEffort(code, errBody)
                                && !token.isCancellationRequested()) {
                            log.info("LLM_STREAM_RETRY_WITHOUT_REASONING_EFFORT model={} status={}", config.modelName(), code);
                            requestConfig = requestConfig.withoutReasoningEffort();
                            continue;
                        }
                        if (requestConfig.promptCacheKeyEnabled()
                                && shouldRetryWithoutPromptCacheKey(code, errBody)
                                && !token.isCancellationRequested()) {
                            log.info("LLM_STREAM_RETRY_WITHOUT_PROMPT_CACHE_KEY model={} status={}", config.modelName(), code);
                            requestConfig = requestConfig.withoutPromptCacheKey();
                            continue;
                        }
                        if (includeUsage && shouldRetryWithoutStreamUsage(code, errBody) && !token.isCancellationRequested()) {
                            log.info("LLM_STREAM_RETRY_WITHOUT_USAGE model={} status={}", config.modelName(), code);
                            includeUsage = false;
                            continue;
                        }
                        ProviderFailure failure = retryPolicy.httpFailure(code, "API error " + code + ": " + errBody);
                        if (retryPolicy.shouldRetry(failure, retries, config)) {
                            int nextRetry = retries + 1;
                            log.warn("LLM_STREAM_RETRY model={} reason=http_{} retry={}", config.modelName(), code, nextRetry);
                            if (retryPolicy.backoff(token, retries++)) {
                                continue;
                            }
                        }
                        emitFailure(onChunk, failure);
                        return;
                    }
                    conn.setReadTimeout(config.effectiveReadTimeoutMs());

                    StringBuilder contentBuf = new StringBuilder();
                    StringBuilder thinkingBuf = new StringBuilder();
                    boolean firstSseEvent = false;
                    boolean terminalEventEmitted = false;
                    AtomicReference<Map<String, Object>> latestUsage = new AtomicReference<>();
                    ToolCallAccumulator toolCalls = new ToolCallAccumulator();
                    InternalReasoningBoundary.VisibleStreamFilter thinkParser = new InternalReasoningBoundary.VisibleStreamFilter(
                            text -> {
                                thinkingBuf.append(text);
                                onChunk.accept(new StreamChunk("thinking_delta", text, null, null,
                                        thinkingBuf.toString(), false, null));
                            },
                            text -> {
                                contentBuf.append(text);
                                onChunk.accept(new StreamChunk("text_delta", text, null, null, null, false, null));
                            });
                    InternalReasoningBoundary.TagStreamFilter explicitThinkingFilter = new InternalReasoningBoundary.TagStreamFilter(text -> {
                        thinkingBuf.append(text);
                        onChunk.accept(new StreamChunk("thinking_delta", text, null, null,
                                thinkingBuf.toString(), false, null));
                    });
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (token.isCancellationRequested()) {
                                emitCancelled(onChunk);
                                return;
                            }
                            String eventLine = line.stripLeading();
                            if (eventLine.isEmpty() || !eventLine.startsWith("data:")) continue;
                            String data = eventLine.substring("data:".length()).stripLeading().trim();
                            if (data.isEmpty()) continue;
                            if (!firstSseEvent) {
                                firstSseEvent = true;
                                log.info("LLM_STREAM_FIRST_EVENT model={} elapsedMs={}", config.modelName(),
                                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt));
                            }
                            if ("[DONE]".equals(data)) {
                                if (!terminalEventEmitted) {
                                    thinkParser.flush();
                                    explicitThinkingFilter.flush();
                                    for (ToolCallAccumulator.ToolCall call : toolCalls.completedCalls()) {
                                        onChunk.accept(toolCallChunk(call, thinkingBuf.toString(), latestUsage.get()));
                                    }
                                    onChunk.accept(new StreamChunk("done", contentBuf.toString(), null, null,
                                            thinkingBuf.toString(), true, latestUsage.get()));
                                    terminalEventEmitted = true;
                                    emittedStreamEvent = true;
                                    log.info("LLM_STREAM_TERMINAL model={} source=done_sentinel", config.modelName());
                                }
                                break;
                            }
                            try {
                                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                                Map<String, Object> usage = extractUsage(chunk);
                                if (usage != null) {
                                    latestUsage.set(usage);
                                    onChunk.accept(new StreamChunk("usage", "", null, null, null, false, usage));
                                    emittedStreamEvent = true;
                                }
                                var choices = chunk.getAsJsonArray("choices");
                                if (choices == null || choices.isEmpty()) continue;
                                JsonObject choice = choices.get(0).getAsJsonObject();
                                JsonObject delta = choice.getAsJsonObject("delta");
                                if (delta != null) {
                                    if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                                        for (var rawCall : delta.getAsJsonArray("tool_calls")) {
                                            ToolCallAccumulator.ToolCallDelta deltaCall = toolCalls.append(rawCall.getAsJsonObject());
                                            if (!deltaCall.argumentsDelta().isEmpty()) {
                                                ToolCallAccumulator.ToolCall call = deltaCall.call();
                                                onChunk.accept(new StreamChunk("tool_args_delta", deltaCall.argumentsDelta(), call.name(),
                                                        call.arguments(), null, false, null, call.id(), call.index(), null));
                                                emittedStreamEvent = true;
                                            }
                                        }
                                    }
                                    if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                        explicitThinkingFilter.push(delta.get("reasoning_content").getAsString());
                                        emittedStreamEvent = true;
                                    }
                                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                        thinkParser.push(delta.get("content").getAsString());
                                        emittedStreamEvent = true;
                                    }
                                }
                                String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                                        ? choice.get("finish_reason").getAsString()
                                        : "";
                                if (!finishReason.isBlank() && !terminalEventEmitted) {
                                    thinkParser.flush();
                                    explicitThinkingFilter.flush();
                                    for (ToolCallAccumulator.ToolCall call : toolCalls.completedCalls()) {
                                        onChunk.accept(toolCallChunk(call, thinkingBuf.toString(), latestUsage.get()));
                                    }
                                    onChunk.accept(new StreamChunk("done", contentBuf.toString(), null, null,
                                            thinkingBuf.toString(), true, latestUsage.get()));
                                    terminalEventEmitted = true;
                                    emittedStreamEvent = true;
                                    log.info("LLM_STREAM_TERMINAL model={} source=finish_reason reason={}",
                                            config.modelName(), finishReason);
                                }
                            } catch (Exception parseEx) {
                                log.debug("SSE parse skip: {}", parseEx.getMessage());
                            }
                        }
                    }
                    if (token.isCancellationRequested()) {
                        emitCancelled(onChunk);
                    }
                    log.info("LLM_STREAM_COMPLETE model={} emittedEvent={} elapsedMs={} completionChars={} thinkingChars={}",
                            config.modelName(), emittedStreamEvent,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt),
                            contentBuf.length(), thinkingBuf.length());
                    return;
                }
            } catch (Exception e) {
                log.warn("LLM_STREAM_FAILURE model={} errorType={} emittedEvent={} message={}", config.modelName(),
                        e.getClass().getSimpleName(), emittedStreamEvent, e.getMessage());
                if (token.isCancellationRequested()) {
                    emitCancelled(onChunk);
                    return;
                }
                ProviderFailure failure = retryPolicy.exceptionFailure(e);
                if (!emittedStreamEvent && retryPolicy.shouldRetry(failure, retries, config)) {
                    int nextRetry = retries + 1;
                    log.warn("LLM_STREAM_RETRY model={} reason={} retry={}", config.modelName(), failure.type(), nextRetry);
                    if (retryPolicy.backoff(token, retries++)) {
                        continue;
                    }
                }
                emitFailure(onChunk, failure);
                return;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    static int initialStreamResponseTimeoutMs(LlmConfig config) {
        return Math.min(config.effectiveReadTimeoutMs(), 30_000);
    }

    private StreamChunk toolCallChunk(ToolCallAccumulator.ToolCall call, String thinking, Map<String, Object> usage) {
        return new StreamChunk("tool_call", "", call.name(), call.arguments(), thinking, true, usage,
                call.id(), call.index(), null);
    }

    private void emitFailure(Consumer<StreamChunk> onChunk, ProviderFailure failure) {
        try {
            onChunk.accept(new StreamChunk("error", failure.message(), null, null, null, true, null,
                    null, null, failure));
        } catch (RuntimeException callbackEx) {
            log.debug("Failed to send provider failure chunk: {}", callbackEx.getMessage());
        }
    }

    private void emitCancelled(Consumer<StreamChunk> onChunk) {
        try {
            onChunk.accept(new StreamChunk("cancelled", "LLM stream cancelled", null, null, null, true, null));
        } catch (RuntimeException callbackEx) {
            log.debug("Failed to send cancellation chunk to callback: {}", callbackEx.getMessage());
        }
    }

    private String buildRequestBody(String sysPrompt, List<Map<String, Object>> msgs,
                                     List<Map<String, Object>> tools, LlmConfig config, boolean stream) {
        return buildRequestBody(sysPrompt, msgs, tools, config, stream, false);
    }

    private String buildRequestBody(String sysPrompt, List<Map<String, Object>> msgs,
                                     List<Map<String, Object>> tools, LlmConfig config, boolean stream,
                                     boolean includeStreamUsage) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.modelName());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", sysPrompt));
        messages.addAll(msgs);
        body.put("messages", messages);

        body.put("max_tokens", config.maxTokens() != null ? config.maxTokens() : 8192);
        if (config.temperature() != null) body.put("temperature", config.temperature());
        if (config.reasoningEffort() != null && !config.reasoningEffort().isBlank()) {
            body.put("reasoning_effort", config.reasoningEffort());
        }
        if (config.promptCacheKeyEnabled() && config.promptCacheKey() != null && !config.promptCacheKey().isBlank()) {
            body.put("prompt_cache_key", config.promptCacheKey());
        }
        if (stream) {
            body.put("stream", true);
            if (includeStreamUsage) {
                body.put("stream_options", Map.of("include_usage", true));
            }
        }

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
            body.put("parallel_tool_calls", false);
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

                    String rawThinking = "";
                    if (msg.has("reasoning_content") && !msg.get("reasoning_content").isJsonNull()) {
                        rawThinking = msg.get("reasoning_content").getAsString();
                    }
                    InternalReasoningBoundary.Projection projection =
                            InternalReasoningBoundary.project(content, rawThinking);
                    content = projection.visible();
                    String thinking = projection.reasoning();

                    if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()) {
                        var tcs = msg.getAsJsonArray("tool_calls");
                        if (tcs != null && !tcs.isEmpty()) {
                            var fn = tcs.get(0).getAsJsonObject().getAsJsonObject("function");
                            if (fn != null) {
                                Map<String, Object> result = new HashMap<>();
                                result.put("type", "tool_call");
                                result.put("tool", fn.get("name").getAsString());
                                result.put("arguments", fn.get("arguments").getAsString());
                                result.put("thinking", thinking);
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
                        result.put("content", "");
                        result.put("thinking", thinking);
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
            boolean cacheUsageReported = hasNestedField(usage, "prompt_tokens_details", "cached_tokens")
                    || hasNestedField(usage, "input_token_details", "cache_read")
                    || hasField(usage, "cache_read_input_tokens")
                    || hasField(usage, "cached_tokens")
                    || hasField(usage, "prompt_cache_hit_tokens")
                    || hasField(usage, "prompt_cache_miss_tokens")
                    || hasNestedField(usage, "input_token_details", "cache_creation")
                    || hasField(usage, "cache_creation_input_tokens")
                    || hasNestedField(usage, "cache_creation", "ephemeral_5m_input_tokens")
                    || hasNestedField(usage, "cache_creation", "ephemeral_1h_input_tokens");
            int cached = firstPositive(
                    nestedInt(usage, "prompt_tokens_details", "cached_tokens"),
                    nestedInt(usage, "input_token_details", "cache_read"),
                    intValue(usage, "cache_read_input_tokens"),
                    intValue(usage, "cached_tokens"),
                    intValue(usage, "prompt_cache_hit_tokens")
            );
            int explicitCacheWrite = firstPositive(
                    nestedInt(usage, "input_token_details", "cache_creation"),
                    intValue(usage, "cache_creation_input_tokens"),
                    nestedInt(usage, "cache_creation", "ephemeral_5m_input_tokens")
                            + nestedInt(usage, "cache_creation", "ephemeral_1h_input_tokens")
            );
            int deepSeekMiss = firstPositive(intValue(usage, "prompt_cache_miss_tokens"), 0);
            int cacheWrite = explicitCacheWrite > 0 ? explicitCacheWrite : deepSeekMiss;
            int prompt = intValue(usage, "prompt_tokens");
            if (prompt == 0) {
                prompt = intValue(usage, "input_tokens") + cached + cacheWrite;
            }
            if (prompt == 0) {
                prompt = intValue(usage, "promptTokenCount");
            }
            int completion = firstPositive(
                    intValue(usage, "completion_tokens"),
                    intValue(usage, "output_tokens"),
                    intValue(usage, "candidatesTokenCount")
            );
            int total = firstPositive(
                    intValue(usage, "total_tokens"),
                    intValue(usage, "totalTokenCount"),
                    prompt + completion
            );
            result.put("prompt_tokens", prompt);
            result.put("completion_tokens", completion);
            result.put("total_tokens", total);
            result.put("cached_tokens", cached);
            result.put("cache_write_tokens", cacheWrite);
            result.put("cache_hit_tokens", intValue(usage, "prompt_cache_hit_tokens"));
            result.put("cache_miss_tokens", deepSeekMiss);
            result.put("cache_usage_reported", cacheUsageReported);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldRetryWithoutReasoningEffort(int statusCode, String errorBody) {
        return statusCode >= 400 && statusCode < 500 && shouldRetryWithoutReasoningEffortMessage(errorBody);
    }

    private boolean shouldRetryWithoutReasoningEffortMessage(String errorBody) {
        String lower = errorBody == null ? "" : errorBody.toLowerCase();
        return lower.contains("reasoning_effort")
                && (lower.contains("unknown field")
                || lower.contains("unrecognized")
                || lower.contains("unsupported parameter")
                || lower.contains("extra inputs are not permitted"));
    }

    private boolean shouldRetryWithoutPromptCacheKey(int statusCode, String errorBody) {
        if (statusCode < 400 || statusCode >= 500) return false;
        String lower = errorBody == null ? "" : errorBody.toLowerCase();
        return lower.contains("prompt_cache_key")
                && (lower.contains("unknown field")
                || lower.contains("unrecognized")
                || lower.contains("unsupported parameter")
                || lower.contains("extra inputs are not permitted"));
    }

    private boolean shouldRetryWithoutStreamUsage(int statusCode, String errorBody) {
        if (statusCode < 400 || statusCode >= 500) return false;
        String lower = errorBody == null ? "" : errorBody.toLowerCase();
        return lower.contains("stream_options")
                || lower.contains("include_usage")
                || lower.contains("unknown field")
                || lower.contains("unrecognized")
                || lower.contains("unsupported parameter")
                || lower.contains("extra inputs are not permitted");
    }

    private boolean hasField(JsonObject root, String fieldName) {
        return root != null && root.has(fieldName) && !root.get(fieldName).isJsonNull();
    }

    private boolean hasNestedField(JsonObject root, String objectName, String fieldName) {
        try {
            if (root == null || !root.has(objectName) || root.get(objectName).isJsonNull()) return false;
            return hasField(root.getAsJsonObject(objectName), fieldName);
        } catch (Exception ignored) {
            return false;
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

    private int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) return value;
        }
        return 0;
    }

    private String httpPost(String url, String body, String apiKey, LlmConfig config,
                            CancellationToken cancellationToken) throws Exception {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        URI uri = outboundUrlPolicy.validate(url).uri();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.effectiveConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        int retries = 0;
        while (true) {
            throwIfCancelled(token);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(config.effectiveReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                CompletableFuture<HttpResponse<String>> responseFuture = client.sendAsync(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                try (CancellationToken.Registration ignored = token.onCancellation(
                        () -> responseFuture.cancel(true))) {
                    HttpResponse<String> response = awaitResponse(responseFuture, token);
                    throwIfCancelled(token);
                    int code = response.statusCode();
                    if (code == 200) {
                        return response.body() == null ? "" : response.body();
                    }
                    ProviderFailure failure = retryPolicy.httpFailure(
                            code, "API error " + code + ": " + (response.body() == null ? "" : response.body()));
                    if (retryPolicy.shouldRetry(failure, retries, config)
                            && retryPolicy.backoff(token, retries++)) {
                        continue;
                    }
                    throw new RuntimeException(failure.message());
                }
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException e) {
                if (token.isCancellationRequested()) {
                    throw new CancellationException("Provider request cancelled");
                }
                throw e;
            } catch (Exception e) {
                if (token.isCancellationRequested()) {
                    throw new CancellationException("Provider request cancelled");
                }
                ProviderFailure failure = retryPolicy.exceptionFailure(e);
                if (retryPolicy.shouldRetry(failure, retries, config)
                        && retryPolicy.backoff(token, retries++)) {
                    continue;
                }
                throw e;
            }
        }
    }

    private HttpResponse<String> awaitResponse(CompletableFuture<HttpResponse<String>> responseFuture,
                                                CancellationToken token) throws Exception {
        try {
            return responseFuture.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (token != null && token.isCancellationRequested()) {
                throw new CancellationException("Provider request cancelled");
            }
            throw interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw execution;
        }
    }

    private void throwIfCancelled(CancellationToken token) {
        if (token != null && token.isCancellationRequested()) {
            throw new CancellationException("Provider request cancelled");
        }
    }

    private Map<String, Object> cancelledResponse() {
        return Map.of("type", "cancelled", "message", "Provider request cancelled", "content", "");
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

    private HttpURLConnection openConnection(String rawUrl) throws Exception {
        URI uri = outboundUrlPolicy.validate(rawUrl).uri();
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private String buildApiUrl(String baseUrl, String endpoint) {
        String normalized = normalizeBaseUrl(baseUrl);
        URI uri = URI.create(normalized);
        String path = uri.getPath();
        if ((path == null || path.isBlank() || "/".equals(path)) && "api.openai.com".equalsIgnoreCase(uri.getHost())) {
            return normalized + "/v1" + endpoint;
        }
        return normalized + endpoint;
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) return "https://api.openai.com";
        url = url.replaceAll("/+$", "");
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null && (path.endsWith("/chat/completions") || path.endsWith("/responses") || path.endsWith("/models"))) {
                int idx = path.lastIndexOf('/');
                String parentPath = idx > 0 ? path.substring(0, idx) : "";
                return new URI(uri.getScheme(), uri.getAuthority(), parentPath, null, null).toString().replaceAll("/+$", "");
            }
            if (path != null && path.endsWith("/compatible-mode")) {
                return new URI(uri.getScheme(), uri.getAuthority(), path + "/v1", null, null).toString().replaceAll("/+$", "");
            }
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            // Let the later URL creation surface invalid custom endpoints.
        }
        return url;
    }

    /**
     * 流式解析 <think>...</think> 标签的状态机
     * 处理标签跨多个 chunk 的情况
     */
}
