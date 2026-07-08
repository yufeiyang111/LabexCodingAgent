package com.labex.labexagent.controller;

import com.labex.common.Result;
import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/student/model-configs")
public class AgentModelConfigController {
    private final AgentModelConfigService configService;
    private final LlmProviderFactory providerFactory;

    public AgentModelConfigController(AgentModelConfigService configService, LlmProviderFactory providerFactory) {
        this.configService = configService;
        this.providerFactory = providerFactory;
    }

    @GetMapping
    public Result<List<AgentModelConfig>> list(Authentication auth) {
        return Result.success(configService.listByStudent(getStudentId(auth)));
    }

    @GetMapping("/{configId}")
    public Result<AgentModelConfig> get(@PathVariable Integer configId, Authentication auth) {
        AgentModelConfig config = configService.getOwned(getStudentId(auth), configId);
        if (config == null) return Result.error("Config not found");
        config.setApiKey(maskKey(config.getApiKey()));
        return Result.success(config);
    }

    @PostMapping
    public Result<AgentModelConfig> create(@RequestBody CreateConfigRequest req, Authentication auth) {
        try {
            AgentModelConfig config = configService.create(
                    getStudentId(auth), req.configName, req.provider, req.modelName,
                    req.apiKey, req.baseUrl, req.maxTokens, req.temperature,
                    Boolean.TRUE.equals(req.isDefault));
            config.setApiKey(maskKey(config.getApiKey()));
            return Result.success(config);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PutMapping("/{configId}")
    public Result<AgentModelConfig> update(@PathVariable Integer configId,
                                            @RequestBody UpdateConfigRequest req, Authentication auth) {
        try {
            AgentModelConfig config = configService.update(
                    getStudentId(auth), configId, req.configName, req.provider,
                    req.modelName, req.apiKey, req.baseUrl, req.maxTokens,
                    req.temperature, req.isDefault);
            config.setApiKey(maskKey(config.getApiKey()));
            return Result.success(config);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @DeleteMapping("/{configId}")
    public Result<Void> delete(@PathVariable Integer configId, Authentication auth) {
        configService.delete(getStudentId(auth), configId);
        return Result.success(null);
    }

    @GetMapping("/providers")
    public Result<Map<String, Object>> providers() {
        return Result.success(providerFactory.getProviderInfo());
    }

    @PostMapping("/{configId}/test")
    public Result<Map<String, Object>> testConnection(@PathVariable Integer configId, Authentication auth) {
        try {
            AgentModelConfig config = configService.getOwned(getStudentId(auth), configId);
            if (config == null) return Result.error("Config not found");

            LlmProvider provider = providerFactory.resolveProvider(config);
            LlmProvider.LlmConfig llmConfig = providerFactory.buildConfig(config);

            long start = System.currentTimeMillis();
            List<java.util.Map<String, Object>> msgs = new java.util.ArrayList<>();
            msgs.add(Map.of("role", "user", "content", "Say exactly: OK"));
            Map<String, Object> resp = provider.chatWithTools(
                    "You are a connection test responder. Reply with exactly what the user asks.",
                    msgs, List.of(), llmConfig);
            long latency = System.currentTimeMillis() - start;

            String type = (String) resp.getOrDefault("type", "unknown");
            if ("error".equals(type)) {
                return Result.success(Map.of(
                        "success", false,
                        "error", resp.getOrDefault("content", "Unknown error"),
                        "latency", latency));
            }
            return Result.success(Map.of(
                    "success", true,
                    "latency", latency,
                    "model", config.getModelName() != null ? config.getModelName() : "unknown",
                    "provider", config.getProvider() != null ? config.getProvider() : "openai_compatible"));
        } catch (Exception e) {
            return Result.success(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Connection failed"));
        }
    }

    @GetMapping("/default")
    public Result<AgentModelConfig> getDefault(Authentication auth) {
        AgentModelConfig config = configService.getDefault(getStudentId(auth));
        if (config != null) config.setApiKey(maskKey(config.getApiKey()));
        return Result.success(config);
    }

    private Integer getStudentId(Authentication auth) {
        return Integer.parseInt(auth.getName());
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    public static class CreateConfigRequest {
        public String configName;
        public String provider;
        public String modelName;
        public String apiKey;
        public String baseUrl;
        public Integer maxTokens;
        public Double temperature;
        public Boolean isDefault;
    }

    public static class UpdateConfigRequest {
        public String configName;
        public String provider;
        public String modelName;
        public String apiKey;
        public String baseUrl;
        public Integer maxTokens;
        public Double temperature;
        public Boolean isDefault;
    }
}
