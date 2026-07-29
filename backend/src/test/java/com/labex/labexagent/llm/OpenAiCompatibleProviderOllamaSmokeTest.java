package com.labex.labexagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.network.OutboundUrlPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "labex.ollama.smoke", matches = "true")
class OpenAiCompatibleProviderOllamaSmokeTest {
    @Test
    void streamsARealResponseFromAnExistingLocalOllamaModel() {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(localOllamaOnlyPolicy());
        List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
        String model = System.getProperty("labex.ollama.model", "gemma3:4b");
        LlmProvider.LlmConfig config = new LlmProvider.LlmConfig(
                "ollama-local-smoke", "http://127.0.0.1:11434/v1", model,
                64, 0.0, 10_000, 180_000, 0);

        provider.chatStream(
                "Reply with a short confirmation that the provider stream works.",
                List.of(Map.of("role", "user", "content", "Reply with exactly: PROVIDER_STREAM_OK")),
                List.of(), config, chunks::add);

        assertThat(chunks).noneMatch(chunk -> "error".equals(chunk.type()));
        assertThat(chunks).anyMatch(chunk -> "done".equals(chunk.type()));
        String text = chunks.stream()
                .filter(chunk -> "text_delta".equals(chunk.type()) || "thinking_delta".equals(chunk.type()))
                .map(LlmProvider.StreamChunk::content)
                .filter(value -> value != null && !value.isBlank())
                .reduce("", String::concat);
        assertThat(text).isNotBlank();
    }

    private OutboundUrlPolicy localOllamaOnlyPolicy() {
        return new OutboundUrlPolicy() {
            @Override
            public ValidatedDestination validate(String rawUrl) {
                URI uri = URI.create(rawUrl);
                assertThat(uri.getScheme()).isEqualTo("http");
                assertThat(uri.getHost()).isEqualTo("127.0.0.1");
                assertThat(uri.getPort()).isEqualTo(11434);
                assertThat(uri.getPath()).startsWith("/v1/");
                return new ValidatedDestination(uri, List.of(InetAddress.getLoopbackAddress()));
            }
        };
    }
}
