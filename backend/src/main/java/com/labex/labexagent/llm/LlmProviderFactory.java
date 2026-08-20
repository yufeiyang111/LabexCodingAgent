package com.labex.labexagent.llm;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.secret.SecretStore;
import com.labex.service.AgentModelConfigService;
import com.labex.rag.config.RagConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderFactory {
    private final Map<String, LlmProvider> providers = new HashMap<>();
    private final RagConfig ragConfig;
    private final SecretStore secretStore;

    public LlmProviderFactory(List<LlmProvider> providerList, RagConfig ragConfig, SecretStore secretStore) {
        this.ragConfig = ragConfig;
        this.secretStore = secretStore;
        for (LlmProvider p : providerList) {
            providers.put(p.getProviderId(), p);
        }
    }

    public LlmProvider getProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return getDefaultProvider();
        }
        LlmProvider p = providers.get(providerId);
        if (p == null) {
            String normalized = providerId.trim().toLowerCase();
            if (normalized.startsWith("langchain4j") || "anthropic".equals(normalized) || "claude".equals(normalized)) {
                p = providers.get("langchain4j");
            }
        }
        if (p == null) {
            p = providers.get("openai_compatible");
        }
        if (p == null && !providers.isEmpty()) {
            p = providers.values().iterator().next();
        }
        return p;
    }

    public LlmProvider getDefaultProvider() {
        return providers.getOrDefault("openai_compatible", providers.values().iterator().next());
    }

    public LlmProvider resolveProvider(AgentModelConfig config) {
        if (config != null && config.getProvider() != null) {
            return getProvider(config.getProvider());
        }
        return getDefaultProvider();
    }

    public LlmProvider.LlmConfig buildConfig(AgentModelConfig config) {
        String configuredKey = resolveConfiguredApiKey(config);
        if (config != null && !configuredKey.isBlank()) {
            return new LlmProvider.LlmConfig(
                    configuredKey,
                    config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com",
                    config.getModelName() != null ? config.getModelName() : "gpt-4o-mini",
                    config.getMaxTokens() != null ? config.getMaxTokens() : AgentModelConfigService.DEFAULT_MAX_TOKENS,
                    config.getTemperature(), null, null, null,
                    Integer.valueOf(1).equals(config.getPromptCacheKeyEnabled()), null
            ).withReasoningEffort(ReasoningEffort.normalize(config.getReasoningEffort()));
        }
        String key = ragConfig.getMiniMaxApiKey();
        if (key == null || key.isBlank()) key = "";
        return new LlmProvider.LlmConfig(
                key,
                ragConfig.getMiniMaxBaseUrl() != null ? ragConfig.getMiniMaxBaseUrl() : "https://api.minimaxi.com/v1",
                ragConfig.getMiniMaxModel() != null ? ragConfig.getMiniMaxModel() : "MiniMax-M2.7",
                AgentModelConfigService.DEFAULT_MAX_TOKENS, null
        );
    }

    private String resolveConfiguredApiKey(AgentModelConfig config) {
        if (config == null) {
            return "";
        }
        if (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank()) {
            try (SecretStore.SecretLease lease = secretStore.open(
                    SecretStore.SecretScope.MODEL_API_KEY, config.getApiKeyEncrypted())) {
                return lease.value();
            }
        }
        return config.getApiKey() == null ? "" : config.getApiKey();
    }

    public Map<String, Object> getProviderInfo() {
        Map<String, Object> info = new HashMap<>();
        for (var entry : providers.entrySet()) {
            Map<String, Object> p = new HashMap<>();
            p.put("id", entry.getValue().getProviderId());
            p.put("name", entry.getValue().getProviderName());
            ProviderCapabilities capabilities = entry.getValue().capabilities();
            p.put("streaming", capabilities.streaming());
            p.put("toolCalling", capabilities.toolCalling());
            p.put("parallelToolCalls", capabilities.parallelToolCalls());
            p.put("reasoning", capabilities.reasoning());
            p.put("usage", capabilities.usage());
            info.put(entry.getKey(), p);
        }
        return info;
    }
}
