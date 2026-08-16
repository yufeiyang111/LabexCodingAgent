package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.labex.labexagent.mcp.McpManager;
import com.labex.labexagent.mcp.McpToolAdapter;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolSchemaCanonicalizer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Provider 工具 schema 的可持久化描述。它只保存名称、schema 与 MCP 路由信息，绝不保存认证信息。
 */
public record ToolExposureSnapshot(int schemaVersion, String runtimeProfile, String mode,
                                   List<Definition> definitions, List<McpDefinition> mcpDefinitions,
                                   String schemaFingerprint) {
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() { }.getType();

    public ToolExposureSnapshot {
        runtimeProfile = runtimeProfile == null ? AgentRuntimeProfile.LABEX_LEGACY.persistedValue() : runtimeProfile;
        mode = AgentMode.normalize(mode);
        definitions = definitions == null ? List.of() : definitions.stream().map(Definition::copyOf).toList();
        mcpDefinitions = mcpDefinitions == null ? List.of() : mcpDefinitions.stream().map(McpDefinition::copyOf).toList();
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
    }

    public static ToolExposureSnapshot live(AgentRuntimeProfile profile, String mode,
                                            List<ToolDefinition> definitions,
                                            List<McpToolAdapter.McpToolInfo> mcpDefinitions) {
        List<Definition> toolDefinitions = (definitions == null ? List.<ToolDefinition>of() : definitions).stream()
                .filter(definition -> definition != null && definition.getName() != null && !definition.getName().isBlank())
                .map(definition -> new Definition(definition.getName(), definition.getDescription(),
                        copyMap(definition.getInputSchema())))
                .toList();
        List<McpDefinition> mcp = (mcpDefinitions == null ? List.<McpToolAdapter.McpToolInfo>of() : mcpDefinitions)
                .stream()
                .filter(info -> info != null && info.getUniqueId() != null && !info.getUniqueId().isBlank())
                .map(info -> new McpDefinition(info.getServerKey(), info.getServerName(), info.getToolName(),
                        info.getDescription(), jsonObjectToMap(info.getInputSchema())))
                .toList();
        List<ToolDefinition> restoredDefinitions = toolDefinitions.stream().map(Definition::toToolDefinition).toList();
        return new ToolExposureSnapshot(CURRENT_SCHEMA_VERSION,
                (profile == null ? AgentRuntimeProfile.LABEX_LEGACY : profile).persistedValue(), mode,
                toolDefinitions, mcp, fingerprint(restoredDefinitions));
    }

    public boolean matches(AgentRuntimeProfile profile, String requestedMode) {
        AgentRuntimeProfile effective = profile == null ? AgentRuntimeProfile.LABEX_LEGACY : profile;
        return schemaVersion == CURRENT_SCHEMA_VERSION
                && effective.persistedValue().equals(runtimeProfile)
                && AgentMode.normalize(requestedMode).equals(mode);
    }

    /** 返回可写入 AgentRunEvent payload 的无敏感信息快照。 */
    public Map<String, Object> toPayload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("runtimeProfile", runtimeProfile);
        payload.put("mode", mode);
        payload.put("schemaFingerprint", schemaFingerprint);
        payload.put("definitions", definitions.stream().map(definition -> {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("name", definition.name());
            row.put("description", definition.description());
            row.put("inputSchema", copyMap(definition.inputSchema()));
            return Map.copyOf(row);
        }).toList());
        payload.put("mcpDefinitions", mcpDefinitions.stream().map(definition -> {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("serverKey", definition.serverKey());
            row.put("serverName", definition.serverName());
            row.put("toolName", definition.toolName());
            row.put("description", definition.description());
            row.put("inputSchema", copyMap(definition.inputSchema()));
            return Map.copyOf(row);
        }).toList());
        return Map.copyOf(payload);
    }

    /** 从 durable event payload 恢复快照；非法、残缺或被篡改的 payload 一律忽略。 */
    public static Optional<ToolExposureSnapshot> fromPayload(Map<String, ?> payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        try {
            int version = intValue(payload.get("schemaVersion"), 0);
            String profile = stringValue(payload.get("runtimeProfile"));
            String mode = stringValue(payload.get("mode"));
            List<Definition> definitions = definitionsFrom(payload.get("definitions"));
            List<McpDefinition> mcpDefinitions = mcpDefinitionsFrom(payload.get("mcpDefinitions"));
            if (version != CURRENT_SCHEMA_VERSION || profile.isBlank() || definitions.isEmpty()) {
                return Optional.empty();
            }
            String expectedFingerprint = fingerprint(definitions.stream().map(Definition::toToolDefinition).toList());
            String storedFingerprint = stringValue(payload.get("schemaFingerprint"));
            if (!storedFingerprint.isBlank() && !storedFingerprint.equals(expectedFingerprint)) {
                return Optional.empty();
            }
            return Optional.of(new ToolExposureSnapshot(version, profile, mode, definitions, mcpDefinitions,
                    expectedFingerprint));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    public List<ToolDefinition> toToolDefinitions() {
        return definitions.stream().map(Definition::toToolDefinition).toList();
    }

    public Map<String, AgentTool> toScopedTools(McpManager manager) {
        if (manager == null || mcpDefinitions.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, AgentTool> result = new LinkedHashMap<>();
        for (McpDefinition definition : mcpDefinitions) {
            McpToolAdapter.McpToolInfo info = definition.toMcpToolInfo();
            if (info.getUniqueId() == null || info.getUniqueId().isBlank()) {
                continue;
            }
            result.putIfAbsent(info.getUniqueId(), new McpToolAdapter(info, manager));
        }
        return Map.copyOf(result);
    }

    public record Definition(String name, String description, Map<String, Object> inputSchema) {
        public Definition {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description;
            inputSchema = copyMap(inputSchema);
        }

        static Definition copyOf(Definition value) {
            return new Definition(value.name(), value.description(), value.inputSchema());
        }

        ToolDefinition toToolDefinition() {
            return new ToolDefinition(name, description, copyMap(inputSchema));
        }
    }

    public record McpDefinition(String serverKey, String serverName, String toolName,
                                String description, Map<String, Object> inputSchema) {
        public McpDefinition {
            serverKey = serverKey == null ? "" : serverKey.trim();
            serverName = serverName == null ? "" : serverName;
            toolName = toolName == null ? "" : toolName.trim();
            description = description == null ? "" : description;
            inputSchema = copyMap(inputSchema);
        }

        static McpDefinition copyOf(McpDefinition value) {
            return new McpDefinition(value.serverKey(), value.serverName(), value.toolName(),
                    value.description(), value.inputSchema());
        }

        McpToolAdapter.McpToolInfo toMcpToolInfo() {
            return new McpToolAdapter.McpToolInfo(serverKey, serverName, toolName, description,
                    mapToJsonObject(inputSchema));
        }
    }

    private static List<Definition> definitionsFrom(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<Definition> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> row)) {
                continue;
            }
            String name = stringValue(row.get("name"));
            if (name.isBlank()) {
                continue;
            }
            result.add(new Definition(name, stringValue(row.get("description")), mapValue(row.get("inputSchema"))));
        }
        return List.copyOf(result);
    }

    private static List<McpDefinition> mcpDefinitionsFrom(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<McpDefinition> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> row)) {
                continue;
            }
            String serverKey = stringValue(row.get("serverKey"));
            String toolName = stringValue(row.get("toolName"));
            if (serverKey.isBlank() || toolName.isBlank()) {
                continue;
            }
            result.add(new McpDefinition(serverKey, stringValue(row.get("serverName")), toolName,
                    stringValue(row.get("description")), mapValue(row.get("inputSchema"))));
        }
        return List.copyOf(result);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return Map.of();
        }
        Map<String, Object> decoded = GSON.fromJson(GSON.toJsonTree(value), MAP_TYPE);
        return copyMap(decoded);
    }

    private static String fingerprint(List<ToolDefinition> definitions) {
        try {
            String canonical = GSON.toJson(ToolSchemaCanonicalizer.openAiTools(definitions));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static Map<String, Object> jsonObjectToMap(JsonObject source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> decoded = GSON.fromJson(source, MAP_TYPE);
        return copyMap(decoded);
    }

    private static JsonObject mapToJsonObject(Map<String, Object> source) {
        return GSON.toJsonTree(copyMap(source)).getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> decoded = GSON.fromJson(GSON.toJsonTree(source), MAP_TYPE);
        return decoded == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(decoded));
    }
}
