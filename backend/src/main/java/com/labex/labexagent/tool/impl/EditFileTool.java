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
public class EditFileTool
implements AgentTool {
    private final DiffService diffService;
    private final StudentProjectService studentProjectService;

    public EditFileTool(DiffService diffService, StudentProjectService studentProjectService) {
        this.diffService = diffService;
        this.studentProjectService = studentProjectService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("edit_file").description("\u7cbe\u786e\u66ff\u6362\u6587\u4ef6\u4e2d\u7684\u6307\u5b9a\u6587\u672c\u7247\u6bb5\uff0c\u6587\u4ef6\u4f1a\u81ea\u52a8\u4fee\u6539\u5e76\u8bb0\u5f55\u53d8\u66f4\u5386\u53f2\uff0c\u652f\u6301\u56de\u9000").stringProperty("file_path", "\u6587\u4ef6\u8def\u5f84", true).stringProperty("old_string", "\u8981\u88ab\u66ff\u6362\u7684\u539f\u59cb\u6587\u672c", true).stringProperty("new_string", "\u66ff\u6362\u540e\u7684\u65b0\u6587\u672c", true).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String afterContent;
        String path = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"file_path", "path", "filePath"});
        String oldString = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"old_string", "oldString", "old_text"});
        String newString = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"new_string", "newString", "new_text"});
        if (path.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        if (oldString.isEmpty()) {
            return ToolResult.failed("old_string is required");
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
        if (!Files.exists(file, new LinkOption[0])) {
            return ToolResult.failed((String)("\u6587\u4ef6\u4e0d\u5b58\u5728: " + path));
        }
        String beforeContent = Files.readString(file);
        if (beforeContent.equals(afterContent = beforeContent.replace(oldString, newString))) {
            return ToolResult.failed("\u672a\u627e\u5230\u8981\u66ff\u6362\u7684\u5185\u5bb9");
        }
        PendingChange change = this.diffService.stageAndApply(context.getStudentId(), context.getProject(), context.getConversationId(), context.getTaskId(), cleaned, beforeContent, afterContent);
        return ToolResult.ok((String)("\u5df2\u81ea\u52a8\u7f16\u8f91\u6587\u4ef6: " + path + "\uff08\u53ef\u968f\u65f6\u56de\u9000\uff09")).withDiff(change.getDiff()).withPendingChangeId(change.getId());
    }
}

