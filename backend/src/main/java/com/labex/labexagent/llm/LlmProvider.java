package com.labex.labexagent.llm;

import com.labex.labexagent.runtime.CancellationToken;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmProvider {

    String getProviderId();

    String getProviderName();

    boolean supportsStreaming();

    boolean supportsToolCalling();

    default ProviderCapabilities capabilities() {
        return new ProviderCapabilities(supportsStreaming(), supportsToolCalling(), false, false, false);
    }

    Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                       List<Map<String, Object>> tools, LlmConfig config);

    default Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                              List<Map<String, Object>> tools, LlmConfig config,
                                              CancellationToken cancellationToken) {
        return chatWithTools(sysPrompt, msgs, tools, config);
    }

    void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                     List<Map<String, Object>> tools, LlmConfig config,
                     Consumer<StreamChunk> onChunk);

    default void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                            List<Map<String, Object>> tools, LlmConfig config,
                            CancellationToken cancellationToken,
                            Consumer<StreamChunk> onChunk) {
        chatStream(sysPrompt, msgs, tools, config, onChunk);
    }

    record LlmConfig(String apiKey, String baseUrl, String modelName,
                     Integer maxTokens, Double temperature, Integer connectTimeoutMs,
                     Integer readTimeoutMs, Integer maxRetries, boolean promptCacheKeyEnabled,
                     String promptCacheKey, String reasoningEffort,
                     String requestOptionsJson,
                     Consumer<OpenAiCompatibleChatRequestAdapter.RequestEvidence> requestEvidenceSink,
                     boolean promptCacheKeyRejected) {
        public LlmConfig(String apiKey, String baseUrl, String modelName,
                         Integer maxTokens, Double temperature) {
            this(apiKey, baseUrl, modelName, maxTokens, temperature, null, null, null, false, null, null, null, null, false);
        }

        public LlmConfig(String apiKey, String baseUrl, String modelName,
                         Integer maxTokens, Double temperature, Integer connectTimeoutMs,
                         Integer readTimeoutMs, Integer maxRetries) {
            this(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs, readTimeoutMs, maxRetries,
                    false, null, null, null, null, false);
        }

        public LlmConfig(String apiKey, String baseUrl, String modelName,
                         Integer maxTokens, Double temperature, Integer connectTimeoutMs,
                         Integer readTimeoutMs, Integer maxRetries, boolean promptCacheKeyEnabled,
                         String promptCacheKey) {
            this(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs, readTimeoutMs, maxRetries,
                    promptCacheKeyEnabled, promptCacheKey, null, null, null, false);
        }

        public LlmConfig(String apiKey, String baseUrl, String modelName,
                         Integer maxTokens, Double temperature, Integer connectTimeoutMs,
                         Integer readTimeoutMs, Integer maxRetries, boolean promptCacheKeyEnabled,
                         String promptCacheKey, String reasoningEffort) {
            this(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs, readTimeoutMs, maxRetries,
                    promptCacheKeyEnabled, promptCacheKey, reasoningEffort, null, null, false);
        }

        public LlmConfig(String apiKey, String baseUrl, String modelName,
                         Integer maxTokens, Double temperature, Integer connectTimeoutMs,
                         Integer readTimeoutMs, Integer maxRetries, boolean promptCacheKeyEnabled,
                         String promptCacheKey, String reasoningEffort, String requestOptionsJson) {
            this(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs, readTimeoutMs, maxRetries,
                    promptCacheKeyEnabled, promptCacheKey, reasoningEffort, requestOptionsJson, null, false);
        }

        public LlmConfig withPromptCacheKey(String value) {
            if (!promptCacheKeyEnabled || value == null || value.isBlank()) return this;
            return new LlmConfig(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs,
                    readTimeoutMs, maxRetries, true, value, reasoningEffort, requestOptionsJson, requestEvidenceSink, promptCacheKeyRejected);
        }

        public LlmConfig withoutPromptCacheKey() {
            if (!promptCacheKeyEnabled && (promptCacheKey == null || promptCacheKey.isBlank())) return this;
            return new LlmConfig(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs,
                    readTimeoutMs, maxRetries, false, null, reasoningEffort, requestOptionsJson, requestEvidenceSink, promptCacheKeyRejected);
        }

        public LlmConfig withReasoningEffort(String value) {
            if (java.util.Objects.equals(reasoningEffort, value)) return this;
            return new LlmConfig(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs,
                    readTimeoutMs, maxRetries, promptCacheKeyEnabled, promptCacheKey, value, requestOptionsJson, requestEvidenceSink, promptCacheKeyRejected);
        }

        public LlmConfig withoutReasoningEffort() {
            return withReasoningEffort("none");
        }

        public LlmConfig withRequestOptionsJson(String value) {
            return new LlmConfig(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs,
                    readTimeoutMs, maxRetries, promptCacheKeyEnabled, promptCacheKey, reasoningEffort, value, requestEvidenceSink, promptCacheKeyRejected);
        }

        public LlmConfig withRequestEvidenceSink(Consumer<OpenAiCompatibleChatRequestAdapter.RequestEvidence> value) {
            return new LlmConfig(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs,
                    readTimeoutMs, maxRetries, promptCacheKeyEnabled, promptCacheKey, reasoningEffort, requestOptionsJson, value, promptCacheKeyRejected);
        }

        public LlmConfig withPromptCacheKeyRejected(boolean value) {
            return new LlmConfig(apiKey, baseUrl, modelName, maxTokens, temperature, connectTimeoutMs,
                    readTimeoutMs, maxRetries, promptCacheKeyEnabled, promptCacheKey, reasoningEffort, requestOptionsJson, requestEvidenceSink, value);
        }

        public boolean reasoningEffortDisabled() {
            return "none".equalsIgnoreCase(reasoningEffort) || "disabled".equalsIgnoreCase(reasoningEffort);
        }

        public int effectiveConnectTimeoutMs() {
            return connectTimeoutMs == null ? 15_000 : Math.max(1_000, Math.min(connectTimeoutMs, 60_000));
        }

        public int effectiveReadTimeoutMs() {
            return readTimeoutMs == null ? 300_000 : Math.max(1_000, Math.min(readTimeoutMs, 900_000));
        }
    }

    record StreamChunk(String type, String content, String toolName,
                       String toolArgs, String thinking, boolean done,
                       Map<String, Object> usage, String toolCallId,
                       Integer toolCallIndex, ProviderFailure failure) {
        public StreamChunk(String type, String content, String toolName,
                           String toolArgs, String thinking, boolean done,
                           Map<String, Object> usage) {
            this(type, content, toolName, toolArgs, thinking, done, usage, null, null, null);
        }

        public ProviderEventType eventType() {
            return ProviderEventType.fromWireType(type);
        }
    }
}
