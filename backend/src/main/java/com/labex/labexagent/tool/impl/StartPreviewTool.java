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
                .description("启动项目的常驻本地预览服务。仅当指定端口真实返回 HTTP 响应后才返回可访问 URL；普通 shell 不应用于保持开发服务器运行。")
                .stringProperty("command", "启动服务的完整命令，例如 npm run dev -- --host 0.0.0.0", true)
                .intProperty("port", "预览服务监听端口，必须与命令实际监听端口一致", true)
                .stringProperty("workdir", "workspace 内相对工作目录，不填使用根目录", false)
                .stringProperty("readiness_path", "用于就绪探测的 HTTP 路径，默认 /", false)
                .stringProperty("working_directory", "兼容字段：映射到 workdir", false)
                .stringProperty("workingDirectory", "兼容字段：映射到 workdir", false)
                .stringProperty("cwd", "兼容字段：映射到 workdir", false)
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
                return ToolResult.failed("preview_status=" + run.status().name().toLowerCase(java.util.Locale.ROOT)
                        + "\nfailure_code=" + run.failureCode()
                        + "\noutput_path=" + run.outputPath()
                        + "\nA preview URL was not issued because HTTP readiness did not succeed.");
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