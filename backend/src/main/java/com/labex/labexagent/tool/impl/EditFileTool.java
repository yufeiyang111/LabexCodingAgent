package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.FileContentFingerprint;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.StudentProjectService;
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
        return ToolDefinition.builder().name("edit_file").description("\u7cbe\u786e\u66ff\u6362\u6587\u4ef6\u4e2d\u7684\u6307\u5b9a\u6587\u672c\u7247\u6bb5\uff0c\u6587\u4ef6\u4f1a\u81ea\u52a8\u4fee\u6539\u5e76\u8bb0\u5f55\u53d8\u66f4\u5386\u53f2\uff0c\u652f\u6301\u56de\u9000").stringProperty("file_path", "\u6587\u4ef6\u8def\u5f84", true).stringProperty("old_string", "\u8981\u88ab\u66ff\u6362\u7684\u539f\u59cb\u6587\u672c", true).stringProperty("new_string", "\u66ff\u6362\u540e\u7684\u65b0\u6587\u672c", true).stringProperty("expected_sha256", "\u4e0a\u4e00\u6b21 read_file \u8fd4\u56de\u7684\u5b8c\u6574\u6587\u4ef6 SHA-256\uff0c\u6587\u4ef6\u53d8\u5316\u540e\u4f1a\u4ee5\u51b2\u7a81\u5931\u8d25", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String path = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"file_path", "path", "filePath"});
        String oldString = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"old_string", "oldString", "old_text"});
        String newString = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"new_string", "newString", "new_text"});
        String expectedSha256 = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"expected_sha256", "expectedSha256", "file_sha256", "sha256"});
        if (path.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        if (oldString.isEmpty()) {
            return ToolResult.failed("old_string is required");
        }
        String cleaned = ToolSupport.normalizeRelativePath((String)path);
        if (cleaned.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        Path file;
        try {
            file = ToolSupport.resolve(context, cleaned);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed("Unsafe file path");
        }
        String beforeContent;
        try {
            beforeContent = ToolSupport.readEditableText(file);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(e.getMessage());
        }
        String actualSha256 = FileContentFingerprint.sha256(beforeContent);
        if (!expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            return ToolResult.failed("code=FILE_CHANGED_SINCE_READ\n"
                    + "message=\u6587\u4ef6\u81ea\u4e0a\u6b21\u8bfb\u53d6\u540e\u5df2\u53d1\u751f\u53d8\u5316\uff0c\u8bf7\u91cd\u65b0\u8bfb\u53d6\u540e\u518d\u7f16\u8f91\n"
                    + "expected_sha256=" + expectedSha256 + "\n"
                    + "actual_sha256=" + actualSha256);
        }
        int firstMatch = beforeContent.indexOf(oldString);
        if (firstMatch < 0) {
            return ToolResult.failed("code=OLD_STRING_NOT_FOUND\n"
                    + "message=\u672a\u627e\u5230\u8981\u66ff\u6362\u7684\u539f\u6587\uff0c\u8bf7\u91cd\u65b0\u8bfb\u53d6\u6587\u4ef6\u540e\u518d\u7f16\u8f91");
        }
        if (beforeContent.indexOf(oldString, firstMatch + oldString.length()) >= 0) {
            return ToolResult.failed("old_string must match exactly once");
        }
        String afterContent = beforeContent.substring(0, firstMatch) + newString
                + beforeContent.substring(firstMatch + oldString.length());
        if (beforeContent.equals(afterContent)) {
            return ToolResult.failed("未找到要替换的内容");
        }
        try {
            ToolSupport.requireEditableTextContent(afterContent);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(e.getMessage());
        }
        PendingChange change = this.diffService.stageAndApplyDeferred(context.getStudentId(), context.getProject(),
                context.getConversationId(), context.getTaskId(), cleaned, beforeContent, afterContent, "modify");
        return ToolResult.ok((String)("\u5df2\u81ea\u52a8\u7f16\u8f91\u6587\u4ef6: " + path + "\uff08\u53ef\u968f\u65f6\u56de\u9000\uff09")).withDiff(change.getDiff()).withPendingChangeId(change.getId());
    }
}
