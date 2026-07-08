package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class WriteFileTool
implements AgentTool {
    private final DiffService diffService;
    private final StudentProjectService studentProjectService;

    public WriteFileTool(DiffService diffService, StudentProjectService studentProjectService) {
        this.diffService = diffService;
        this.studentProjectService = studentProjectService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("write_file").description("\u521b\u5efa\u65b0\u6587\u4ef6\u6216\u5b8c\u5168\u8986\u76d6\u5df2\u6709\u6587\u4ef6\u7684\u5185\u5bb9\uff0c\u6587\u4ef6\u4f1a\u81ea\u52a8\u5199\u5165\u5e76\u8bb0\u5f55\u53d8\u66f4\u5386\u53f2\uff0c\u652f\u6301\u56de\u9000").stringProperty("file_path", "\u6587\u4ef6\u8def\u5f84", true).stringProperty("content", "\u6587\u4ef6\u5185\u5bb9", true).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String path = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"file_path", "path", "filePath"});
        String content = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"content", "file_content", "fileContent"});
        if (path.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        Path root = context.getWorkspaceRoot();
        String cleaned = ToolSupport.normalizeRelativePath((String)path);
        if (cleaned.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        Path file = root.resolve(cleaned).normalize();
        if (!file.startsWith(root)) {
            return ToolResult.failed("Unsafe file path");
        }
        String beforeContent = "";
        if (Files.exists(file, new LinkOption[0])) {
            beforeContent = Files.readString(file);
        }
        PendingChange change = this.diffService.stageAndApply(context.getStudentId(), context.getProject(), context.getConversationId(), context.getTaskId(), cleaned, beforeContent, content);
        return ToolResult.ok((String)("\u5df2\u81ea\u52a8\u5199\u5165\u6587\u4ef6: " + path + "\uff08\u53ef\u968f\u65f6\u56de\u9000\uff09")).withDiff(change.getDiff()).withPendingChangeId(change.getId());
    }
}

