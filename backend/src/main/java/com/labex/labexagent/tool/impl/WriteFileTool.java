package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.workspace.WorkspaceOperationIdentity;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WriteFileTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);

    private final DiffService diffService;
    private final StudentProjectService studentProjectService;

    public WriteFileTool(DiffService diffService, StudentProjectService studentProjectService) {
        this.diffService = diffService;
        this.studentProjectService = studentProjectService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("write_file")
                .description("Create one text file or intentionally replace the entire content of one existing file. Use apply_patch for contextual partial edits or file deletion. Changes are applied immediately, recorded in history, and can be reverted.")
                .stringProperty("file_path", "File path", true)
                .stringProperty("content", "File content", true)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        long totalStartedNanos = System.nanoTime();
        String path = ToolSupport.stringArgMulti(args, "", "file_path", "path", "filePath");
        String content = ToolSupport.stringArgMulti(args, "", "content", "file_content", "fileContent");
        if (path.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        try {
            ToolSupport.requireEditableTextContent(content);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(e.getMessage());
        }
        String cleaned = ToolSupport.normalizeRelativePath(path);
        if (cleaned.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        log.info("WRITE_FILE_TOOL_START taskId={} projectId={} conversationId={} path={} contentChars={}",
                context.getTaskId(), context.getProject().getProjectId(), context.getConversationId(), cleaned,
                content.length());
        Path file;
        try {
            file = ToolSupport.resolveForCreate(context, cleaned);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed("Unsafe file path");
        }
        String beforeContent = "";
        long readStartedNanos = System.nanoTime();
        boolean fileExists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (fileExists) {
            try {
                beforeContent = ToolSupport.readEditableText(file);
            } catch (IllegalArgumentException e) {
                return ToolResult.failed(e.getMessage());
            }
        }
        long readElapsedMs = elapsedMs(readStartedNanos);
        log.info("WRITE_FILE_TOOL_SOURCE_READY taskId={} path={} existed={} readMs={} beforeChars={}",
                context.getTaskId(), cleaned, fileExists, readElapsedMs, beforeContent.length());
        long applyStartedNanos = System.nanoTime();
        try {
            PendingChange change = this.diffService.stageAndApplyDeferred(context.getStudentId(), context.getProject(),
                    context.getConversationId(), context.getTaskId(), cleaned, beforeContent, content,
                    fileExists ? "modify" : "create");
            long applyElapsedMs = elapsedMs(applyStartedNanos);
            long totalElapsedMs = elapsedMs(totalStartedNanos);
            log.info("WRITE_FILE_TOOL_COMPLETE taskId={} path={} changeId={} applyMs={} totalMs={}",
                    context.getTaskId(), cleaned, change.getId(), applyElapsedMs, totalElapsedMs);
            DiffService.ApplyTelemetry telemetry = this.diffService.peekLastApplyTelemetry();
            Map<String, Object> workspaceVerification = telemetry == null ? Map.of()
                    : telemetry.workspaceVerification();
            return ToolResult.ok("\u5df2\u81ea\52a8\u5199\u5165\u6587\u4ef6: " + path
                            + "\uff08\u53ef\u968f\u65f6\u56de\u9000\uff09")
                    .withDiff(change.getDiff()).withPendingChangeId(change.getId())
                    .withWorkspaceChangeEvidence(
                            WorkspaceOperationIdentity.forContext(context, context.getWorkspaceRoot(), List.of(cleaned)),
                            List.of(change.getId()),
                            telemetry == null ? Map.of() : telemetry.workspaceMutation())
                    .withWorkspaceVerification(workspaceVerification);
        } catch (Exception failure) {
            log.warn("WRITE_FILE_TOOL_FAILED taskId={} path={} applyMs={} totalMs={} errorType={} error={}",
                    context.getTaskId(), cleaned, elapsedMs(applyStartedNanos), elapsedMs(totalStartedNanos),
                    failure.getClass().getSimpleName(), failure.getMessage());
            throw failure;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
