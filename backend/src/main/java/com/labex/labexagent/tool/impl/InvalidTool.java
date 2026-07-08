package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 处理无效/无法解析的工具调用（参考 OpenCode InvalidTool）
 * 当 LLM 返回无法识别的工具名或参数格式错误时，返回友好错误信息而非崩溃
 */
@Component
public class InvalidTool implements AgentTool {

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
            .name("invalid")
            .description("Internal error handler for invalid tool calls. Do not call directly.")
            .stringProperty("reason", "error reason", false)
            .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String reason = args.has("reason") ? args.get("reason").getAsString() : "unknown";
        return ToolResult.failed(
            "Invalid tool call: " + reason + ". " +
            "Please check the tool name and parameters, then try again with a valid tool."
        );
    }
}
