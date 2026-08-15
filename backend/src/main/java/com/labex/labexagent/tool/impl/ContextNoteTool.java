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
                .description("Save durable engineering context, such as architectural decisions, constraints, and verification findings. Future tasks can retrieve these notes.")
                .stringProperty("title", "Short title", true)
                .stringProperty("content", "Engineering fact or decision to retain", true)
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
