package com.labex.labexagent.llm;

import com.labex.entity.AgentModelConfig;
import com.labex.rag.config.RagConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderFactory {
    private final Map<String, LlmProvider> providers = new HashMap<>();
    private final RagConfig ragConfig;

    public LlmProviderFactory(List<LlmProvider> providerList, RagConfig ragConfig) {
        this.ragConfig = ragConfig;
        for (LlmProvider p : providerList) {
            providers.put(p.getProviderId(), p);
        }
    }

    public LlmProvider getProvider(String providerId) {
        LlmProvider p = providers.get(providerId);
        if (p == null) p = providers.get("openai_compatible");
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
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return new LlmProvider.LlmConfig(
                    config.getApiKey(),
                    config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com",
                    config.getModelName() != null ? config.getModelName() : "gpt-4o-mini",
                    config.getMaxTokens() != null ? config.getMaxTokens() : 32768,
                    config.getTemperature()
            );
        }
        String key = ragConfig.getMiniMaxApiKey();
        if (key == null || key.isBlank()) key = "";
        return new LlmProvider.LlmConfig(
                key,
                ragConfig.getMiniMaxBaseUrl() != null ? ragConfig.getMiniMaxBaseUrl() : "https://api.minimaxi.com/v1",
                ragConfig.getMiniMaxModel() != null ? ragConfig.getMiniMaxModel() : "MiniMax-M2.7",
                32768, null
        );
    }

    public Map<String, Object> getProviderInfo() {
        Map<String, Object> info = new HashMap<>();
        for (var entry : providers.entrySet()) {
            Map<String, Object> p = new HashMap<>();
            p.put("id", entry.getValue().getProviderId());
            p.put("name", entry.getValue().getProviderName());
            p.put("streaming", entry.getValue().supportsStreaming());
            p.put("toolCalling", entry.getValue().supportsToolCalling());
            info.put(entry.getKey(), p);
        }
        return info;
    }
}
