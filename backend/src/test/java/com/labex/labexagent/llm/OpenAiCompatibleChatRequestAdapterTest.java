package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleChatRequestAdapterTest {

    private final OpenAiCompatibleChatRequestAdapter adapter = new OpenAiCompatibleChatRequestAdapter();

    @Test
    void preservesCurrentDefaultReasoningBehaviorWhenNoAdvancedOptionsExist() {
        OpenAiCompatibleChatRequestAdapter.PreparedRequest request = adapter.adapt(
                "system", List.of(Map.of("role", "user", "content", "hello")), List.of(),
                new LlmProvider.LlmConfig("key", "https://example.test", "model", 128, 0.2),
                false, false);

        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertEquals("model", body.get("model").getAsString());
        assertEquals("medium", body.get("reasoning_effort").getAsString());
        assertFalse(body.has("thinking"));
        assertFalse(body.has("budget"));
        assertEquals("Bearer key", request.headers().get("Authorization"));
    }

    @Test
    void mapsCustomReasoningThinkingBudgetAndNestedOverrides() {
        String options = """
                {
                  "reasoning": {
                    "defaultLevel": "deep",
                    "allowedLevels": ["fast", "deep"],
                    "path": "/reasoning/effort",
                    "mapping": {"deep": "maximum"}
                  },
                  "thinking": {
                    "mode": "boolean",
                    "path": "/thinking/enabled",
                    "defaultEnabled": true
                  },
                  "budget": {
                    "enabled": true,
                    "path": "/thinking/budget_tokens",
                    "mapping": {"deep": 8192}
                  },
                  "requestOverrides": {
                    "body": {"provider": {"route": "fast"}, "top_p": 0.9},
                    "headers": {"X-Custom-Route": "fast"}
                  }
                }
                """;
        LlmProvider.LlmConfig config = new LlmProvider.LlmConfig(
                "key", "https://example.test", "model", 128, 0.2,
                null, null, null, false, null, "deep", options);

        OpenAiCompatibleChatRequestAdapter.PreparedRequest request = adapter.adapt(
                "system", List.of(), List.of(), config, false, false);
        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();

        assertEquals("maximum", body.getAsJsonObject("reasoning").get("effort").getAsString());
        assertTrue(body.getAsJsonObject("thinking").get("enabled").getAsBoolean());
        assertEquals(8192, body.getAsJsonObject("thinking").get("budget_tokens").getAsInt());
        assertEquals("fast", body.getAsJsonObject("provider").get("route").getAsString());
        assertEquals(0.9, body.get("top_p").getAsDouble());
        assertEquals("fast", request.headers().get("X-Custom-Route"));
        assertTrue(request.evidence().appliedConfigPaths().contains("/reasoning/path"));
        assertTrue(request.evidence().requestShapeDigest().length() >= 32);
        assertEquals(1, request.evidence().messageFingerprints().size());
    }

    @Test
    void keepsOpenAiCompatiblePromptCachingImplicitWithoutInlineCacheHints() {
        OpenAiCompatibleChatRequestAdapter.PreparedRequest request = adapter.adapt(
                "system", List.of(Map.of("role", "user", "content", "hello")),
                List.of(Map.of("type", "function", "function", Map.of("name", "read_file"))),
                new LlmProvider.LlmConfig("key", "https://example.test", "model", 128, 0.2,
                        null, null, null, true, "route-key"),
                false, false);

        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertTrue(body.has("prompt_cache_key"));
        assertFalse(request.body().contains("cache_control"));
    }

    @Test
    void doesNotAllowOverridesToReplaceDynamicAgentFieldsOrSecurityHeaders() {
        String bodyOverride = "{\"requestOverrides\":{\"body\":{\"messages\":[]}}}";
        LlmProvider.LlmConfig bodyConfig = new LlmProvider.LlmConfig(
                "key", "https://example.test", "model", 128, 0.2,
                null, null, null, false, null, "medium", bodyOverride);
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(
                "system", List.of(), List.of(), bodyConfig, false, false));

        String headerOverride = "{\"requestOverrides\":{\"headers\":{\"Authorization\":\"Bearer other\"}}}";
        LlmProvider.LlmConfig headerConfig = new LlmProvider.LlmConfig(
                "key", "https://example.test", "model", 128, 0.2,
                null, null, null, false, null, "medium", headerOverride);
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(
                "system", List.of(), List.of(), headerConfig, false, false));
    }
}
