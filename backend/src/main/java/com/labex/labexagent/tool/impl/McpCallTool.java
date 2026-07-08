package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.AgentMcpServerService;
import org.springframework.stereotype.Component;

@Component
public class McpCallTool implements AgentTool {
    private final AgentMcpServerService mcpServerService;

    public McpCallTool(AgentMcpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("mcp_call")
                .description("Call an enabled user-level MCP HTTP JSON-RPC tool. Use only when a configured server is relevant.")
                .stringProperty("server_key", "configured MCP server key", true)
                .stringProperty("tool_name", "remote tool name", true)
                .stringProperty("arguments_json", "JSON object string passed as tool arguments", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String serverKey = ToolSupport.stringArg(args, "server_key", "");
        String toolName = ToolSupport.stringArg(args, "tool_name", "");
        String argumentsJson = ToolSupport.stringArg(args, "arguments_json", "{}");
        if (serverKey.isBlank() || toolName.isBlank()) {
            return ToolResult.failed("server_key and tool_name are required");
        }
        return ToolResult.ok(mcpServerService.callTool(context.getStudentId(), serverKey, toolName, argumentsJson));
    }
}
