package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.AgentWorkspaceMemoryService;
import com.labex.labexagent.service.ProjectCodeMapService;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

@Component
public class RepoMapTool implements AgentTool {
    private final ProjectCodeMapService projectCodeMapService;
    private final AgentWorkspaceMemoryService workspaceMemoryService;

    public RepoMapTool(ProjectCodeMapService projectCodeMapService,
                       AgentWorkspaceMemoryService workspaceMemoryService) {
        this.projectCodeMapService = projectCodeMapService;
        this.workspaceMemoryService = workspaceMemoryService;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("repo_map")
                .description("Build a compact structured repository map: files, symbols, routes and imports ranked by a query. Use before reading large files.")
                .stringProperty("query", "task keywords, symbol name, route, API name, or feature area", false)
                .intProperty("max_files", "maximum files to return, default 18", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String query = ToolSupport.stringArg(args, "query", "");
        int maxFiles = Math.min(60, Math.max(4, ToolSupport.intArg(args, "max_files", 18)));
        String map = projectCodeMapService.buildRepoMap(
                context.getProject(),
                query,
                workspaceMemoryService.readMemory(context.getProject()).touchedFiles,
                maxFiles
        );
        return ToolResult.ok(map);
    }
}
