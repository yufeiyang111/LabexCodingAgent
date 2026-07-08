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
        return ToolDefinition.builder().name("project_overview").description("\u751f\u6210\u7d27\u51d1\u9879\u76ee\u6982\u89c8\u548c\u76f8\u5173\u7247\u6bb5\u3002\u9002\u5408\u4efb\u52a1\u5f00\u59cb\u65f6\u5148\u5efa\u7acb\u7d22\u5f15\uff0c\u51cf\u5c11 token\u3002").stringProperty("query", "\u5f53\u524d\u4efb\u52a1\u5173\u952e\u8bcd", false).build();
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
