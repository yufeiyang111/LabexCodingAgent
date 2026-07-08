package com.labex.labexagent.mcp;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;

import java.util.List;

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
        ToolDefinition.Builder builder = ToolDefinition.builder()
            .name(toolInfo.getUniqueId())
            .description("[MCP:" + toolInfo.getServerName() + "] " + toolInfo.getDescription());

        // 从 inputSchema 提取属性定义
        JsonObject schema = toolInfo.getInputSchema();
        if (schema != null && schema.has("properties")) {
            JsonObject properties = schema.getAsJsonObject("properties");
            List<String> required = new java.util.ArrayList<>();
            if (schema.has("required")) {
                schema.getAsJsonArray("required").forEach(e -> required.add(e.getAsString()));
            }

            for (String key : properties.keySet()) {
                JsonObject prop = properties.getAsJsonObject(key);
                String desc = prop.has("description") ? prop.get("description").getAsString() : key;
                boolean isRequired = required.contains(key);
                builder = builder.stringProperty(key, desc, isRequired);
            }
        }

        return builder.build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String argsJson = args != null ? args.toString() : "{}";
        McpClient.CallResult result = mcpManager.callTool(
            context.getStudentId(),
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
