package com.labex.labexagent.llm.langchain4j;

import com.labex.labexagent.llm.LlmProvider;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Factory for creating LangChain4j ChatLanguageModel and StreamingChatLanguageModel instances based on LlmConfig.
 */
@Component
public class Langchain4jModelFactory {

    public StreamingChatLanguageModel createStreamingModel(LlmProvider.LlmConfig config) {
        Duration timeout = Duration.ofMillis(config.effectiveReadTimeoutMs());

        if (isAnthropic(config)) {
            var builder = AnthropicStreamingChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .timeout(timeout);

            if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                builder.baseUrl(normalizeAnthropicBaseUrl(config.baseUrl()));
            }
            if (config.temperature() != null) {
                builder.temperature(config.temperature());
            }
            if (config.maxTokens() != null) {
                builder.maxTokens(config.maxTokens());
            }
            return builder.build();
        }

        // Default: OpenAI-compatible
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(config.apiKey() != null && !config.apiKey().isBlank() ? config.apiKey() : "no-key")
                .modelName(config.modelName())
                .timeout(timeout);

        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(normalizeOpenAiBaseUrl(config.baseUrl()));
        }
        if (config.temperature() != null) {
            builder.temperature(config.temperature());
        }
        if (config.maxTokens() != null) {
            builder.maxTokens(config.maxTokens());
        }

        return builder.build();
    }

    public ChatLanguageModel createSyncModel(LlmProvider.LlmConfig config) {
        Duration timeout = Duration.ofMillis(config.effectiveReadTimeoutMs());

        if (isAnthropic(config)) {
            var builder = AnthropicChatModel.builder()
                    .apiKey(config.apiKey())
                    .modelName(config.modelName())
                    .timeout(timeout);

            if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                builder.baseUrl(normalizeAnthropicBaseUrl(config.baseUrl()));
            }
            if (config.temperature() != null) {
                builder.temperature(config.temperature());
            }
            if (config.maxTokens() != null) {
                builder.maxTokens(config.maxTokens());
            }
            return builder.build();
        }

        // Default: OpenAI-compatible
        var builder = OpenAiChatModel.builder()
                .apiKey(config.apiKey() != null && !config.apiKey().isBlank() ? config.apiKey() : "no-key")
                .modelName(config.modelName())
                .timeout(timeout);

        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(normalizeOpenAiBaseUrl(config.baseUrl()));
        }
        if (config.temperature() != null) {
            builder.temperature(config.temperature());
        }
        if (config.maxTokens() != null) {
            builder.maxTokens(config.maxTokens());
        }

        return builder.build();
    }

    private static boolean isAnthropic(LlmProvider.LlmConfig config) {
        if (config.baseUrl() != null && config.baseUrl().toLowerCase().contains("anthropic")) {
            return true;
        }
        return config.modelName() != null && config.modelName().toLowerCase().startsWith("claude");
    }

    private static String normalizeOpenAiBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.endsWith("/v1") && !trimmed.contains("/v1/")) {
            trimmed = trimmed + "/v1";
        }
        return trimmed;
    }

    private static String normalizeAnthropicBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
