package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.preview.ProjectPreviewService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

/** 启动受控常驻预览服务；URL 只在 Worker 子进程真实通过 HTTP readiness 后返回。 */
@Component
public class StartPreviewTool implements AgentTool {
    private final ProjectPreviewService previewService;

    public StartPreviewTool(ProjectPreviewService previewService) {
        this.previewService = previewService;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("start_preview")
                .description("Start a persistent local preview service for the project. Return a URL only after the requested port serves a real HTTP response; do not use ordinary shell execution to keep a development server alive.")
                .stringProperty("command", "Complete command that starts the service, for example npm run dev -- --host 0.0.0.0", true)
                .intProperty("port", "Port the preview service listens on; it must match the port used by the command", true)
                .stringProperty("workdir", "Workspace-relative working directory; defaults to the workspace root", false)
                .stringProperty("readiness_path", "HTTP path used for readiness checks; default /", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String command = ToolSupport.stringArgMulti(args, "", "command", "cmd", "shell_command");
        String workdir = ToolSupport.stringArgMulti(args, ".", "workdir", "working_directory", "workingDirectory", "cwd");
        String readinessPath = ToolSupport.stringArgMulti(args, "/", "readiness_path", "readinessPath");
        int port = ToolSupport.intArg(args, "port", 0);
        try {
            ProjectPreviewService.PreviewRun run = previewService.start(new ProjectPreviewService.StartRequest(
                    context.getStudentId(), context.getProject().getProjectId(), context.getTaskId(),
                    context.getWorkspaceRoot(), workdir, command, port, readinessPath));
            if (!run.ready()) {
                StringBuilder failure = new StringBuilder("preview_status=")
                        .append(run.status().name().toLowerCase(java.util.Locale.ROOT))
                        .append("\nfailure_code=").append(run.failureCode())
                        .append("\noutput_path=").append(run.outputPath());
                if (!run.failureHint().isBlank()) {
                    failure.append("\nuntrusted_failure_output_tail=").append(run.failureHint());
                }
                failure.append("\nA preview URL was not issued because HTTP readiness did not succeed.");
                return ToolResult.failed(failure.toString());
            }
            StringBuilder content = new StringBuilder("preview_status=ready")
                    .append("\npreview_id=").append(run.previewId())
                    .append("\npreview_url=").append(run.publicUrl())
                    .append("\nprocess_id=").append(run.processId())
                    .append("\noutput_path=").append(run.outputPath());
            if (run.lastHttpStatus() != null) content.append("\nhttp_status=").append(run.lastHttpStatus());
            content.append("\nThe preview URL passed HTTP readiness. Report this exact URL, and do not claim success if a later status check shows exited.");
            return ToolResult.ok(content.toString());
        } catch (IllegalArgumentException invalid) {
            return ToolResult.failed("preview_status=failed\nfailure_code=invalid_request\n" + invalid.getMessage());
        }
    }
}