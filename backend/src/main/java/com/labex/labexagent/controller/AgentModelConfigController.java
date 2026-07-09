package com.labex.labexagent.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.common.Result;
import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.service.AgentModelConfigService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    @PostMapping({"/models", "/model-list"})
    public Result<Map<String, Object>> listModels(@RequestBody ModelListRequest req) {
        try {
            String url = req.modelsUrl != null && !req.modelsUrl.isBlank()
                    ? req.modelsUrl.trim()
                    : buildModelsUrl(req.baseUrl);
            validatePublicHttpsUrl(url);

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (req.apiKey != null && !req.apiKey.isBlank()) {
                applyModelListAuth(conn, URI.create(url), req.apiKey.trim());
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(false);

            int status = conn.getResponseCode();
            String body = readAll(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
            conn.disconnect();

            if (status < 200 || status >= 300) {
                return Result.success(Map.of(
                        "success", false,
                        "error", "Models endpoint returned HTTP " + status,
                        "models", List.of()));
            }

            List<Map<String, Object>> models = parseModels(body);
            return Result.success(Map.of(
                    "success", true,
                    "modelsUrl", url,
                    "models", models));
        } catch (Exception e) {
            return Result.success(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to fetch models",
                    "models", List.of()));
        }
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

    private String buildModelsUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1/models";
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        URI uri = URI.create(normalized);
        String path = uri.getPath();
        if (path != null && path.endsWith("/chat/completions")) {
            int idx = path.lastIndexOf('/');
            path = idx > 0 ? path.substring(0, idx) : "";
            try {
                normalized = new URI(uri.getScheme(), uri.getAuthority(), path, null, null).toString().replaceAll("/+$", "");
            } catch (Exception ignored) {
                // Keep the user-provided URL path when URI reconstruction fails.
            }
        }
        URI normalizedUri = URI.create(normalized);
        String normalizedPath = normalizedUri.getPath();
        if ((normalizedPath == null || normalizedPath.isBlank() || "/".equals(normalizedPath))
                && "api.openai.com".equalsIgnoreCase(normalizedUri.getHost())) {
            return normalized + "/v1/models";
        }
        if (normalizedPath != null && normalizedPath.endsWith("/compatible-mode")) {
            return normalized + "/v1/models";
        }
        return normalized + "/models";
    }

    private void validatePublicHttpsUrl(String rawUrl) throws Exception {
        URI uri = URI.create(rawUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Models URL must use HTTPS");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Models URL must not include credentials");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Models URL must use the default HTTPS port");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Models URL host is required");
        }
        String lowerHost = host.toLowerCase();
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".localhost")) {
            throw new IllegalArgumentException("Models URL host is not allowed");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("Models URL must resolve to a public address");
            }
        }
    }

    private void applyModelListAuth(HttpURLConnection conn, URI uri, String apiKey) {
        String host = uri.getHost();
        if (host != null && host.equalsIgnoreCase("generativelanguage.googleapis.com")) {
            conn.setRequestProperty("x-goog-api-key", apiKey);
            return;
        }
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
    }

    private boolean isBlockedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0x80);
        }
        return false;
    }

    private List<Map<String, Object>> parseModels(String body) {
        List<Map<String, Object>> models = new ArrayList<>();
        JsonElement root = JsonParser.parseString(body);
        JsonArray data = null;
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) data = obj.getAsJsonArray("data");
            else if (obj.has("models") && obj.get("models").isJsonArray()) data = obj.getAsJsonArray("models");
        } else if (root.isJsonArray()) {
            data = root.getAsJsonArray();
        }
        if (data == null) return models;

        for (JsonElement item : data) {
            String id = null;
            String owner = null;
            if (item.isJsonPrimitive()) {
                id = item.getAsString();
            } else if (item.isJsonObject()) {
                JsonObject obj = item.getAsJsonObject();
                id = firstString(obj, "id", "name", "model", "model_id");
                owner = firstString(obj, "owned_by", "owner", "provider");
            }
            if (id == null || id.isBlank()) continue;
            if (id.startsWith("models/")) {
                id = id.substring("models/".length());
            }
            Map<String, Object> model = new HashMap<>();
            model.put("id", id);
            if (owner != null && !owner.isBlank()) model.put("owner", owner);
            if (item.isJsonObject()) {
                Integer maxTokens = firstInt(item.getAsJsonObject(),
                        "max_output_tokens", "maxOutputTokens",
                        "output_token_limit", "outputTokenLimit",
                        "max_completion_tokens", "maxCompletionTokens",
                        "completion_token_limit", "completionTokenLimit");
                if (maxTokens != null && maxTokens > 0) {
                    model.put("maxTokens", maxTokens);
                }
            }
            models.add(model);
        }
        models.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
        return models;
    }

    private String firstString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull() && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsString();
            }
        }
        return null;
    }

    private Integer firstInt(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) {
                continue;
            }
            try {
                return obj.get(key).getAsInt();
            } catch (Exception ignored) {
                // Try the next compatible metadata key.
            }
        }
        return null;
    }

    private String readAll(java.io.InputStream is) {
        if (is == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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

    public static class ModelListRequest {
        public String baseUrl;
        public String modelsUrl;
        public String apiKey;
    }
}
