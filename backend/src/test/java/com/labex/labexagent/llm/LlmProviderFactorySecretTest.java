package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.secret.LocalEnvelopeSecretStore;
import com.labex.labexagent.secret.SecretStore;
import com.labex.rag.config.RagConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmProviderFactorySecretTest {

    @Test
    void buildsRuntimeConfigurationFromEncryptedModelCredentials() {
        SecretStore secrets = new LocalEnvelopeSecretStore(masterKey(), false);
        SecretStore.StoredSecret stored = secrets.store(SecretStore.SecretScope.MODEL_API_KEY, "sk-runtime-secret");
        AgentModelConfig config = new AgentModelConfig();
        config.setApiKey("");
        config.setApiKeyEncrypted(stored.ciphertext());
        config.setBaseUrl("https://api.example.test");
        config.setModelName("test-model");
        config.setPromptCacheKeyEnabled(1);
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getProviderId()).thenReturn("openai_compatible");

        LlmProviderFactory factory = new LlmProviderFactory(List.of(provider), new RagConfig(), secrets);

        LlmProvider.LlmConfig runtime = factory.buildConfig(config);
        assertEquals("sk-runtime-secret", runtime.apiKey());
        assertEquals(true, runtime.promptCacheKeyEnabled());
    }


    @Test
    void usesOpenCodeAlignedDefaultOutputLimitButPreservesExplicitUserValue() {
        SecretStore secrets = new LocalEnvelopeSecretStore(masterKey(), false);
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getProviderId()).thenReturn("openai_compatible");
        LlmProviderFactory factory = new LlmProviderFactory(List.of(provider), new RagConfig(), secrets);
        AgentModelConfig config = new AgentModelConfig();
        config.setApiKey("sk-runtime-secret");

        assertEquals(32_000, factory.buildConfig(config).maxTokens());

        config.setMaxTokens(12_345);
        assertEquals(12_345, factory.buildConfig(config).maxTokens());
    }
    private String masterKey() {
        return Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    }
}
