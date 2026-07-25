package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.secret.LocalEnvelopeSecretStore;
import com.labex.labexagent.secret.SecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AgentModelConfigSecretTest {

    @Test
    void storesModelApiKeysAsEncryptedSecretsInsteadOfPlaintext() {
        SecretStore secrets = new LocalEnvelopeSecretStore(masterKey(), false);
        AgentModelConfigService service = spy(new AgentModelConfigService(secrets));
        doAnswer(invocation -> true).when(service).save(any(AgentModelConfig.class));

        AgentModelConfig config = service.create(
                7, "Default", "openai_compatible", "gpt-test", "sk-model-secret",
                "https://api.example.test", 1024, 1_000_000, null, false);

        assertEquals(1_000_000, config.getContextWindowTokens());
        assertEquals("", config.getApiKey());
        assertNotNull(config.getApiKeyEncrypted());
        assertFalse(config.getApiKeyEncrypted().contains("sk-model-secret"));
        try (SecretStore.SecretLease lease = secrets.open(
                SecretStore.SecretScope.MODEL_API_KEY, config.getApiKeyEncrypted())) {
            assertEquals("sk-model-secret", lease.value());
        }
    }

    private String masterKey() {
        return Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    }
}
