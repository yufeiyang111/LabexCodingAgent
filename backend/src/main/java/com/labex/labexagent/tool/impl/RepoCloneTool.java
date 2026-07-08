package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

// 已禁用：始终返回失败，白白占用 token
// @Component
public class RepoCloneTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("repo_clone").description("Repository clone request. Student workspaces should upload projects or use shell with approval for git clone.").stringProperty("url", "repository URL", true).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String url = ToolSupport.stringArg((JsonObject)args, "url", "");
        if (url.isBlank()) {
            return ToolResult.failed("url is required");
        }
        return ToolResult.failed("repo_clone is disabled in student workspaces for safety. Ask the user to upload the project, or request an approved shell command if cloning is necessary.");
    }
}
