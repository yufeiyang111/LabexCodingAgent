package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.WorkspaceScanner;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.AgentToolDiscoveryProperties;
import com.labex.labexagent.tool.FileContentFingerprint;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReadFileTool implements AgentTool {
    private final StudentProjectService studentProjectService;
    private final WorkspaceScanner workspaceScanner;
    private final AgentToolDiscoveryProperties discoveryProperties;

    /** 兼容已有聚焦测试和旧调用方；生产 Bean 使用注入的共享扫描器与配置。 */
    public ReadFileTool(StudentProjectService studentProjectService) {
        this(studentProjectService, new WorkspaceScanner(), new AgentToolDiscoveryProperties());
    }

    /** 兼容已有两参数调用方；生产 Bean 使用三参数构造器。 */
    public ReadFileTool(StudentProjectService studentProjectService, WorkspaceScanner workspaceScanner) {
        this(studentProjectService, workspaceScanner, new AgentToolDiscoveryProperties());
    }

    @Autowired
    public ReadFileTool(StudentProjectService studentProjectService, WorkspaceScanner workspaceScanner,
            AgentToolDiscoveryProperties discoveryProperties) {
        this.studentProjectService = studentProjectService;
        this.workspaceScanner = workspaceScanner;
        this.discoveryProperties = discoveryProperties;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("read_file")
                .description("Read a file at the specified path, optionally within a line range. Use glob or list_files first when the path is uncertain.")
                .stringProperty("file_path", "Workspace-relative file path", true)
                .intProperty("offset", "Starting line number (optional)", false)
                .intProperty("limit", "Number of lines to read; default 2000", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String path = ToolSupport.normalizeRelativePath(
                ToolSupport.stringArgMulti(args, "", "file_path", "path", "filePath"));
        if (path.isEmpty()) {
            return ToolResult.failed("code=FILE_PATH_REQUIRED\nfile_path is required");
        }
        final String content;
        try {
            content = studentProjectService.readProjectFile(context.getStudentId(), context.getProject().getProjectId(), path);
        } catch (IllegalArgumentException missingPath) {
            if (!"path does not exist".equals(missingPath.getMessage())) {
                throw missingPath;
            }
            return missingFile(path, context);
        }
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
                .append(" sha256=").append(FileContentFingerprint.sha256(content))
                .append("]\n");
        for (int index = offset; index < end; ++index) {
            result.append(index + 1).append(": ").append(lines[index]).append("\n");
        }
        return ToolResult.ok(result.toString());
    }

    private ToolResult missingFile(String requestedPath, AgentContext context) {
        List<String> candidates = candidates(requestedPath, context);
        StringBuilder result = new StringBuilder("code=FILE_NOT_FOUND\nrequested_path=")
                .append(requestedPath).append("\n");
        if (!candidates.isEmpty()) {
            result.append("candidates=\n");
            candidates.forEach(candidate -> result.append("- ").append(candidate).append("\n"));
            result.append("next_action=use a candidate path or call glob before retrying");
        } else {
            result.append("next_action=call glob or list_files to locate the file before retrying");
        }
        return ToolResult.failed(result.toString());
    }

    private List<String> candidates(String requestedPath, AgentContext context) {
        String targetName;
        try {
            Path fileName = Path.of(requestedPath).getFileName();
            targetName = fileName == null ? "" : fileName.toString();
        } catch (RuntimeException invalidPath) {
            return List.of();
        }
        if (targetName.isBlank()) {
            return List.of();
        }
        String expected = targetName.toLowerCase(java.util.Locale.ROOT);
        SecureWorkspacePath paths;
        try {
            paths = ToolSupport.workspacePaths(context);
        } catch (IllegalArgumentException unavailableWorkspace) {
            return List.of();
        }
        List<String> exact = new ArrayList<>();
        List<String> related = new ArrayList<>();
        try {
            workspaceScanner.scan(paths, WorkspaceScanner.INTERACTIVE_SEARCH_BUDGET, context.getCancellationToken(),
                    (file, attributes) -> {
                        String relative = paths.workspaceRoot().relativize(file).toString().replace('\\', '/');
                        String name = file.getFileName() == null ? "" : file.getFileName().toString();
                        String normalizedName = name.toLowerCase(java.util.Locale.ROOT);
                        if (normalizedName.equals(expected)) {
                            exact.add(relative);
                        } else if (normalizedName.contains(expected) || expected.contains(normalizedName)) {
                            related.add(relative);
                        }
                        return true;
                    });
        } catch (Exception candidateScanFailure) {
            // 文件读取的原始失败结果仍然可信；候选建议只是安全的尽力恢复信息。
            return List.of();
        }
        int candidateLimit = discoveryProperties.getReadFileCandidateLimit();
        exact.sort(Comparator.naturalOrder());
        related.sort(Comparator.naturalOrder());
        List<String> results = new ArrayList<>(candidateLimit);
        exact.stream().limit(candidateLimit).forEach(results::add);
        if (results.size() < candidateLimit) {
            related.stream().filter(candidate -> !results.contains(candidate))
                    .limit(candidateLimit - results.size()).forEach(results::add);
        }
        return List.copyOf(results);
    }
}