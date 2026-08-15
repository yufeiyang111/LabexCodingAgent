package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.AgentWorkspaceMemoryService;
import com.labex.labexagent.service.ProjectCodeMapService;
import com.labex.labexagent.service.ProjectIndexService;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

@Component
public class ProjectOverviewTool
implements AgentTool {
    private final ProjectIndexService projectIndexService;
    private final AgentWorkspaceMemoryService workspaceMemoryService;
    private final ProjectCodeMapService projectCodeMapService;

    public ProjectOverviewTool(ProjectIndexService projectIndexService,
                               AgentWorkspaceMemoryService workspaceMemoryService,
                               ProjectCodeMapService projectCodeMapService) {
        this.projectIndexService = projectIndexService;
        this.workspaceMemoryService = workspaceMemoryService;
        this.projectCodeMapService = projectCodeMapService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("project_overview").description("Generate a compact project overview and relevant snippets. Use it at the start of a task to build an index and reduce token use.").stringProperty("query", "Keywords for the current task", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String query = ToolSupport.stringArg((JsonObject)args, "query", "");
        String adaptive = this.projectIndexService.buildAdaptiveProjectContext(
                context.getProject(),
                query,
                this.workspaceMemoryService.readMemory(context.getProject()).touchedFiles
        );
        String memory = this.workspaceMemoryService.buildMemoryContext(context.getProject(), query, "");
        String repoMap = this.projectCodeMapService.buildRepoMap(
                context.getProject(),
                query,
                this.workspaceMemoryService.readMemory(context.getProject()).touchedFiles,
                18
        );
        return ToolResult.ok(adaptive + "\n\n" + repoMap + "\n\n" + memory);
    }
}
