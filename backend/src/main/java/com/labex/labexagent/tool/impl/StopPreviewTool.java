package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.preview.ProjectPreviewService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

/** 停止一个由 start_preview 创建的受控预览进程。 */
@Component
public class StopPreviewTool implements AgentTool {
    private final ProjectPreviewService previewService;

    public StopPreviewTool(ProjectPreviewService previewService) {
        this.previewService = previewService;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("stop_preview")
                .description("Stop a specified managed preview service. Use only a preview_id returned by start_preview.")
                .stringProperty("preview_id", "Preview run ID returned by start_preview", true)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String previewId = ToolSupport.stringArg(args, "preview_id", "");
        try {
            ProjectPreviewService.PreviewRun run = previewService.stop(
                    context.getStudentId(), context.getProject().getProjectId(), previewId);
            return ToolResult.ok("preview_status=" + run.status().name().toLowerCase(java.util.Locale.ROOT)
                    + "\npreview_id=" + run.previewId());
        } catch (IllegalArgumentException invalid) {
            return ToolResult.failed("preview_status=failed\nfailure_code=preview_not_found\n" + invalid.getMessage());
        }
    }
}