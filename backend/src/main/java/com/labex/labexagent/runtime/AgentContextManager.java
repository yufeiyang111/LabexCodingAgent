package com.labex.labexagent.runtime;

import com.labex.entity.StudentProject;
import com.labex.labexagent.service.ProjectIndexService;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class AgentContextManager {
    private final ProjectIndexService projectIndexService;

    public AgentContextManager(ProjectIndexService projectIndexService) {
        this.projectIndexService = projectIndexService;
    }

    public String buildInitialContext(StudentProject project, String activePath, String activeFileContent, String toolDefinitions, String userMessage) {
        return this.buildInitialContext(project, activePath, activeFileContent, toolDefinitions, userMessage, "", false);
    }

    public String buildInitialContext(StudentProject project, String activePath, String activeFileContent, String toolDefinitions, String userMessage, boolean smallModel) {
        return this.buildInitialContext(project, activePath, activeFileContent, toolDefinitions, userMessage, "", smallModel);
    }

    public String buildInitialContext(StudentProject project, String activePath, String activeFileContent, String toolDefinitions, String userMessage, String projectIndexContent, boolean smallModel) {
        int structureLimit = smallModel ? 4000 : 10000;
        int contentLimit = smallModel ? 8000 : 30000;
        int projectIndexLimit = smallModel ? 6000 : 16000;
        StringBuilder context = new StringBuilder();
        context.append("\u9879\u76ee\u540d\u79f0: ").append(project.getProjectName()).append('\n');
        // \u9879\u76ee\u7ed3\u6784\u5df2\u5728\u7cfb\u7edf\u63d0\u793a\u8bcd\u7684 <environment> \u4e2d\u5305\u542b\uff0c\u6b64\u5904\u4e0d\u518d\u91cd\u590d
        if (projectIndexContent != null && !projectIndexContent.isBlank()) {
            context.append("\u6301\u4e45\u9879\u76ee\u7d22\u5f15 `.labex/project-index.md`\uff08\u4f18\u5148\u7528\u4e8e\u5b9a\u4f4d\u6587\u4ef6\uff0c\u5fc5\u8981\u65f6\u518d\u8bfb\u53d6\u5b8c\u6574\u6e90\u7801\uff09:\n").append(this.limit(projectIndexContent, projectIndexLimit)).append("\n\n");
        } else if (!smallModel) {
            context.append(this.projectIndexService.buildProjectDigest(project, userMessage)).append("\n\n");
        }
        if (activePath != null && !activePath.isBlank()) {
            context.append("\u5f53\u524d\u6253\u5f00\u6587\u4ef6: ").append(activePath).append('\n');
            context.append("\u5f53\u524d\u6587\u4ef6\u5185\u5bb9:\n```").append('\n').append(this.limit(activeFileContent, contentLimit)).append("\n```\n\n");
        }
        return context.toString();
    }

    public String compactTranscript(String transcript) {
        return this.smartLimit(transcript, 70000, 12000);
    }

    public String summarizeObservation(String toolName, String text, boolean success) {
        return this.pruneToolResult(toolName, text, success, false);
    }

    public String compactToolResult(String toolName, String text) {
        return this.pruneToolResult(toolName, text, true, true);
    }

    public String compactToolResult(String toolName, String text, boolean success) {
        return this.pruneToolResult(toolName, text, success, true);
    }

    public String compactCheckpointResult(String toolName, String text, boolean success) {
        return this.pruneToolResult(toolName, text, success, true, 2600);
    }

    private String pruneToolResult(String toolName, String text, boolean success, boolean forModel) {
        return this.pruneToolResult(toolName, text, success, forModel, forModel ? 5500 : 9000);
    }

    private String pruneToolResult(String toolName, String text, boolean success, boolean forModel, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String safeTool = this.normalizeTool(toolName);
        if (!success) {
            return this.withPruneHeader("failed", safeTool, this.errorFocused(text, Math.min(maxChars, forModel ? 3600 : 6000)));
        }
        if (this.isReadTool(safeTool)) {
            return this.withPruneHeader("read", safeTool, this.smartLimit(text, Math.min(maxChars, forModel ? 5200 : 8000), forModel ? 2600 : 3800));
        }
        if (this.isSearchTool(safeTool)) {
            return this.withPruneHeader("search", safeTool, this.firstLines(text, forModel ? 90 : 140, Math.min(maxChars, forModel ? 4500 : 7600)));
        }
        if (this.isShellTool(safeTool)) {
            return this.withPruneHeader("terminal", safeTool, this.terminalFocused(text, Math.min(maxChars, forModel ? 4200 : 7000)));
        }
        if (this.isWriteTool(safeTool)) {
            return this.withPruneHeader("write", safeTool, this.smartLimit(text, Math.min(maxChars, forModel ? 3200 : 5200), forModel ? 1400 : 2200));
        }
        return this.withPruneHeader("generic", safeTool, this.smartLimit(text, Math.min(maxChars, forModel ? 3600 : 6200), forModel ? 1600 : 2600));
    }

    private boolean isReadTool(String safeTool) {
        return "read_file".equals(safeTool) || "read".equals(safeTool);
    }

    private boolean isSearchTool(String safeTool) {
        return "search_code".equals(safeTool) || "codesearch".equals(safeTool) || "grep".equals(safeTool) || "list_files".equals(safeTool) || "glob".equals(safeTool) || "retrieve_context".equals(safeTool) || "project_overview".equals(safeTool) || "repo_overview".equals(safeTool);
    }

    private boolean isShellTool(String safeTool) {
        return "shell".equals(safeTool) || "bash".equals(safeTool) || "run_command".equals(safeTool) || "run_tests".equals(safeTool) || "execute_code".equals(safeTool);
    }

    private boolean isWriteTool(String safeTool) {
        return "write_file".equals(safeTool) || "write".equals(safeTool) || "edit_file".equals(safeTool) || "edit".equals(safeTool) || "apply_patch".equals(safeTool) || "patch".equals(safeTool);
    }

    private String normalizeTool(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }

    private String withPruneHeader(String strategy, String tool, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return "[tool_result_pruned strategy=" + strategy + (String)(tool.isBlank() ? "" : " tool=" + tool) + "]\n" + body;
    }

    private String firstLines(String text, int maxLines, int maxChars) {
        String[] lines = text.split("\\R");
        StringBuilder builder = new StringBuilder();
        int omitted = 0;
        for (String line : lines) {
            if (builder.length() + line.length() + 1 > maxChars || maxLines <= 0) {
                ++omitted;
                continue;
            }
            builder.append(line).append('\n');
            --maxLines;
        }
        if (omitted > 0 || lines.length > maxLines) {
            builder.append("...\u5df2\u88c1\u526a\u5176\u4f59\u641c\u7d22/\u5217\u8868\u7ed3\u679c\uff0c\u8bf7\u6309\u9700\u7ee7\u7eed read_file/search_code \u83b7\u53d6\u66f4\u5177\u4f53\u5185\u5bb9...\n");
        }
        return builder.toString().trim();
    }

    private String terminalFocused(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String[] lines = text.split("\\R");
        StringBuilder important = new StringBuilder();
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("error") && !lower.contains("failed") && !lower.contains("exception") && !lower.contains("traceback") && !lower.contains("cannot") && !lower.contains("not found") && !lower.contains("\u5931\u8d25") && !lower.contains("\u9519\u8bef") || important.length() + line.length() >= maxChars / 2) continue;
            important.append(line).append('\n');
        }
        String tail = text.substring(Math.max(0, text.length() - Math.max(1200, maxChars - important.length() - 400)));
        return (String)(important.isEmpty() ? "" : "Key diagnostic lines:\n" + String.valueOf(important) + "\n") + "...\u7ec8\u7aef\u8f93\u51fa\u5df2\u88c1\u526a\uff0c\u4fdd\u7559\u9519\u8bef\u7ebf\u7d22\u548c\u5c3e\u90e8...\n" + tail;
    }

    private String errorFocused(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String terminal = this.terminalFocused(text, maxChars);
        if (terminal.length() <= maxChars + 300) {
            return terminal;
        }
        return this.smartLimit(terminal, maxChars, Math.min(1200, maxChars / 2));
    }

    private String legacyCompactToolResult(String toolName, String text) {
        String safeTool;
        String string = safeTool = toolName == null ? "" : toolName;
        if ("read_file".equals(safeTool)) {
            return this.smartLimit(text, 16000, 5000);
        }
        if ("search_code".equals(safeTool) || "list_files".equals(safeTool) || "retrieve_context".equals(safeTool)) {
            return this.smartLimit(text, 8000, 2500);
        }
        if ("shell".equals(safeTool) || "run_command".equals(safeTool) || "run_tests".equals(safeTool) || "execute_code".equals(safeTool)) {
            return this.smartLimit(text, 10000, 2000);
        }
        return this.smartLimit(text, 6000, 1500);
    }

    private String limit(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "\n...\u4e0a\u4e0b\u6587\u5df2\u538b\u7f29\u622a\u65ad...";
    }

    private String smartLimit(String text, int max, int head) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        int tail = Math.max(0, max - head - 1200);
        return text.substring(0, Math.min(head, text.length())) + "\n\n...\u4e2d\u95f4\u5386\u53f2\u5df2\u667a\u80fd\u538b\u7f29\uff0c\u4fdd\u7559\u5f00\u5934\u76ee\u6807\u4e0e\u6700\u8fd1\u5173\u952e\u4e8b\u4ef6...\n\n" + text.substring(Math.max(0, text.length() - tail));
    }
}

