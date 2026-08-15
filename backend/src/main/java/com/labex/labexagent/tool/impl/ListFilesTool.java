package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.service.WorkspaceScanner;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class ListFilesTool implements AgentTool {
    private static final int MAX_ENTRIES = 500;
    private final WorkspaceScanner workspaceScanner;

    public ListFilesTool(WorkspaceScanner workspaceScanner) {
        this.workspaceScanner = workspaceScanner;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("list_files").description("Recursively list a directory tree while ignoring common build, dependency, and cache directories.")
                .stringProperty("path", "Directory path; defaults to the workspace root", false)
                .intProperty("max_depth", "Maximum recursion depth; default 3", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String path = ToolSupport.normalizeRelativePath(args.has("path") ? args.get("path").getAsString() : ".");
        int maxDepth = Math.min(10, Math.max(0, ToolSupport.intArg(args, "max_depth", 3)));
        SecureWorkspacePath paths;
        Path target;
        try {
            paths = ToolSupport.workspacePaths(context);
            target = paths.resolveExisting(path.isEmpty() || path.equals(".") ? "." : path);
        } catch (IllegalArgumentException e) {
            return ToolResult.failed("Unsafe path");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return ToolResult.failed("Path does not exist: " + path);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return ToolResult.ok(target.getFileName().toString());
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return ToolResult.failed("Path is not a directory: " + path);

        AtomicInteger emitted = new AtomicInteger();
        ProjectScanPolicy.ScanIgnoreRules ignoreRules = workspaceScanner.ignoreRules(paths);
        String tree = buildTree(target, paths, ignoreRules, 0, maxDepth, emitted);
        if (emitted.get() >= MAX_ENTRIES) {
            tree += "\n\nListing truncated: entry limit reached (max_entries=" + MAX_ENTRIES + ").";
        }
        return ToolResult.ok(tree);
    }

    private String buildTree(Path directory, SecureWorkspacePath paths, ProjectScanPolicy.ScanIgnoreRules ignoreRules,
                             int depth, int maxDepth, AtomicInteger emitted) {
        if (depth >= maxDepth || emitted.get() >= MAX_ENTRIES) return "";
        try (Stream<Path> entries = Files.list(directory)) {
            StringBuilder tree = new StringBuilder();
            var iterator = entries.filter(path -> isVisibleDirectoryEntry(paths, ignoreRules, path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .iterator();
            while (iterator.hasNext() && emitted.get() < MAX_ENTRIES) {
                Path entry = iterator.next();
                String prefix = "  ".repeat(depth);
                String name = entry.getFileName().toString();
                emitted.incrementAndGet();
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    tree.append(prefix).append("[D] ").append(name).append("/\n");
                    tree.append(buildTree(entry, paths, ignoreRules, depth + 1, maxDepth, emitted));
                } else {
                    tree.append(prefix).append("[F] ").append(name).append('\n');
                }
            }
            return tree.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isVisibleDirectoryEntry(SecureWorkspacePath paths, ProjectScanPolicy.ScanIgnoreRules ignoreRules,
                                            Path entry) {
        if (!workspaceScanner.isSafeWorkspaceEntry(paths, entry)) return false;
        return !ignoreRules.shouldSkipEntry(entry, Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS));
    }
}
