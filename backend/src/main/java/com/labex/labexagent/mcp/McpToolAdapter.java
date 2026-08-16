package com.labex.labexagent.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.WorkerRunSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具适配器
 * 将 MCP 工具转换为 AgentTool 接口实现
 */
public class McpToolAdapter implements AgentTool {

    private final McpToolInfo toolInfo;
    private final McpManager mcpManager;

    /**
     * MCP 工具信息
     */
    public static class McpToolInfo {
        private final String serverKey;
        private final String serverName;
        private final String toolName;
        private final String description;
        private final JsonObject inputSchema;

        public McpToolInfo(String serverKey, String serverName, String toolName, String description, JsonObject inputSchema) {
            this.serverKey = serverKey;
            this.serverName = serverName;
            this.toolName = toolName;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        public String getServerKey() { return serverKey; }
        public String getServerName() { return serverName; }
        public String getToolName() { return toolName; }
        public String getDescription() { return description; }
        public JsonObject getInputSchema() { return inputSchema; }

        /**
         * 生成唯一的工具名称
         */
        public String getUniqueId() {
            return "mcp_" + serverKey + "_" + toolName;
        }
    }

    public McpToolAdapter(McpToolInfo toolInfo, McpManager mcpManager) {
        this.toolInfo = toolInfo;
        this.mcpManager = mcpManager;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(toolInfo.getUniqueId(),
                "[MCP:" + toolInfo.getServerName() + "] " + toolInfo.getDescription(),
                copyInputSchema(toolInfo.getInputSchema()));
    }

    /**
     * MCP server 的 JSON Schema 是本工具调用契约，不能为了通用展示而把所有字段降级为 string。
     */
    private Map<String, Object> copyInputSchema(JsonObject source) {
        Map<String, Object> schema = source == null ? new LinkedHashMap<>() : toMap(source);
        if (!schema.containsKey("type")) {
            schema.put("type", "object");
        }
        if (!schema.containsKey("properties")) {
            schema.put("properties", new LinkedHashMap<>());
        }
        return schema;
    }

    private Map<String, Object> toMap(JsonObject source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            result.put(entry.getKey(), toJavaValue(entry.getValue()));
        }
        return result;
    }

    private Object toJavaValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            return toMap(value.getAsJsonObject());
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            List<Object> result = new ArrayList<>(array.size());
            for (JsonElement item : array) {
                result.add(toJavaValue(item));
            }
            return result;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String argsJson = args != null ? args.toString() : "{}";
        McpClient.CallResult result = mcpManager.callTool(
            context.getStudentId(),
            WorkerRunSpec.forWorkspace("mcp-" + context.getSessionId(), context.getWorkspaceRoot()),
            toolInfo.getServerKey(),
            toolInfo.getToolName(),
            argsJson
        );

        if (result.isSuccess()) {
            return ToolResult.ok(result.getContent());
        } else {
            return ToolResult.failed(result.getError());
        }
    }
}
