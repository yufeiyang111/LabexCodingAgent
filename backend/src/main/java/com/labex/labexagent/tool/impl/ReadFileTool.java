package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.StudentProjectService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

@Component
public class ReadFileTool
implements AgentTool {
    private final StudentProjectService studentProjectService;

    public ReadFileTool(StudentProjectService studentProjectService) {
        this.studentProjectService = studentProjectService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("read_file").description("\u8bfb\u53d6\u6307\u5b9a\u8def\u5f84\u7684\u6587\u4ef6\u5185\u5bb9\uff0c\u652f\u6301\u8bbe\u7f6e\u884c\u53f7\u8303\u56f4").stringProperty("file_path", "\u6587\u4ef6\u7684\u7edd\u5bf9\u6216\u76f8\u5bf9\u8def\u5f84", true).intProperty("offset", "\u4ece\u7b2c\u51e0\u884c\u5f00\u59cb\u8bfb\u53d6\uff08\u53ef\u9009\uff09", false).intProperty("limit", "\u8bfb\u53d6\u591a\u5c11\u884c\uff08\u9ed8\u8ba42000\u884c\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String path = ToolSupport.normalizeRelativePath((String)ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"file_path", "path", "filePath"}));
        if (path.isEmpty()) {
            return ToolResult.failed("file_path is required");
        }
        String content = this.studentProjectService.readProjectFile(context.getStudentId(), context.getProject().getProjectId(), path);
        int offset = Math.max(0, args.has("offset") ? args.get("offset").getAsInt() : 0);
        int limit = Math.max(1, args.has("limit") ? args.get("limit").getAsInt() : 2000);
        String[] lines = content.split("\n", -1);
        offset = Math.min(offset, lines.length);
        int end = Math.min(offset + limit, lines.length);
        StringBuilder result = new StringBuilder();
        result.append("[read_file path=").append(path)
                .append(" lines=").append(lines.length == 0 ? 0 : offset + 1)
                .append("-").append(end)
                .append("/").append(lines.length)
                .append(" sha256=").append(shortHash(content))
                .append("]\n");
        for (int i = offset; i < end; ++i) {
            result.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return ToolResult.ok((String)result.toString());
    }

    private String shortHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(8, bytes.length); i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
