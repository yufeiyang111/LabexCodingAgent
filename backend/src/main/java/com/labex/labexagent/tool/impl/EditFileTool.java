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
        return ToolDefinition.builder().name("edit_file").description("Precisely replace a specified text block in a file. Changes are applied immediately, recorded in history, and can be reverted.").stringProperty("file_path", "File path", true).stringProperty("old_string", "Existing text to replace", true).stringProperty("new_string", "Replacement text", true).stringProperty("expected_sha256", "Full-file SHA-256 returned by the last read_file call; changes fail with a conflict if the file has changed", false).build();
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
        boolean isCrlf = beforeContent.contains("\r\n");
        String normBefore = beforeContent.replace("\r\n", "\n");
        String normOld = oldString.replace("\r\n", "\n");
        String normNew = newString.replace("\r\n", "\n");

        MatchResult match = findMatch(normBefore, normOld);
        if (match == null) {
            int exactCount = countOccurrences(normBefore, normOld);
            if (exactCount > 1) {
                return ToolResult.failed("old_string must match exactly once");
            }
            return ToolResult.failed("code=OLD_STRING_NOT_FOUND\n"
                    + "message=未找到要替换的原文，请重新读取文件后再编辑");
        }

        String normAfter = normBefore.substring(0, match.startIndex) + normNew
                + normBefore.substring(match.startIndex + match.length);
        String afterContent = isCrlf ? normAfter.replace("\n", "\r\n") : normAfter;

        if (beforeContent.equals(afterContent)) {
            return ToolResult.failed("code=NO_OP_EDIT\n"
                    + "message=new_string is identical to the matched old_string");
        }
        try {
            ToolSupport.requireEditableTextContent(afterContent);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(e.getMessage());
        }
        PendingChange change = this.diffService.stageAndApplyDeferred(context.getStudentId(), context.getProject(),
                context.getConversationId(), context.getTaskId(), cleaned, beforeContent, afterContent, "modify");
        return ToolResult.ok("已自动编辑文件: " + path + "（可随时回退）").withDiff(change.getDiff()).withPendingChangeId(change.getId());
    }

    private record MatchResult(int startIndex, int length) {}

    private MatchResult findMatch(String content, String find) {
        int idx = content.indexOf(find);
        if (idx >= 0) {
            int second = content.indexOf(find, idx + 1);
            if (second >= 0) {
                return null;
            }
            return new MatchResult(idx, find.length());
        }

        String[] originalLines = content.split("\n", -1);
        String[] searchLines = find.split("\n", -1);
        if (searchLines.length > 0 && searchLines[searchLines.length - 1].isEmpty()) {
            String[] trimmed = new String[searchLines.length - 1];
            System.arraycopy(searchLines, 0, trimmed, 0, trimmed.length);
            searchLines = trimmed;
        }
        if (searchLines.length == 0) return null;

        int matchCount = 0;
        int foundStart = -1;
        int foundLength = -1;

        for (int i = 0; i <= originalLines.length - searchLines.length; i++) {
            boolean matches = true;
            for (int j = 0; j < searchLines.length; j++) {
                if (!originalLines[i + j].trim().equals(searchLines[j].trim())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                matchCount++;
                if (matchCount > 1) {
                    return null;
                }
                int start = 0;
                for (int k = 0; k < i; k++) {
                    start += originalLines[k].length() + 1;
                }
                int end = start;
                for (int k = 0; k < searchLines.length; k++) {
                    end += originalLines[i + k].length();
                    if (k < searchLines.length - 1) {
                        end += 1;
                    }
                }
                foundStart = start;
                foundLength = end - start;
            }
        }

        if (matchCount == 1) {
            return new MatchResult(foundStart, foundLength);
        }
        return null;
    }

    private int countOccurrences(String content, String find) {
        if (find.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(find, idx)) >= 0) {
            count++;
            idx += find.length();
        }
        return count;
    }
}
