package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.AgentWorkspaceMemoryService;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

@Component
public class ContextNoteTool implements AgentTool {
    private final AgentWorkspaceMemoryService workspaceMemoryService;

    public ContextNoteTool(AgentWorkspaceMemoryService workspaceMemoryService) {
        this.workspaceMemoryService = workspaceMemoryService;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("context_note")
                .description("记录长期工程上下文，例如架构决策、关键约束、验证结论。后续任务会自动读取这些记忆。")
                .stringProperty("title", "简短标题", true)
                .stringProperty("content", "要长期记住的工程事实或决策", true)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String title = ToolSupport.stringArg(args, "title", "");
        String content = ToolSupport.stringArg(args, "content", "");
        if (title.isBlank() || content.isBlank()) {
            return ToolResult.failed("title and content are required");
        }
        workspaceMemoryService.recordDecision(context, title, content);
        return ToolResult.ok("Context note saved: " + title);
    }
}
