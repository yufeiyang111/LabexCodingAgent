package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import org.springframework.stereotype.Component;

// 已禁用：始终返回失败，白白占用 token
// @Component
public class ExternalDirectoryTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("external_directory").description("External directory request. Labex restricts file access to the current student workspace.").stringProperty("path", "external directory path", true).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        return ToolResult.failed("External directories are not available in Labex student workspaces. Use files inside the current project workspace.");
    }
}
