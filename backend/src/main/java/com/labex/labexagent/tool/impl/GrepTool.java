package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.WorkspaceScanner;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

@Component
public class GrepTool implements AgentTool {
    private static final long MAX_FILE_BYTES = 300_000;
    private static final long MAX_TOTAL_BYTES = 8_000_000;
    private final WorkspaceScanner workspaceScanner;

    public GrepTool(WorkspaceScanner workspaceScanner) {
        this.workspaceScanner = workspaceScanner;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("grep")
                .description("Search code with a regular expression and return paths, line numbers, and brief context. Prefer it to broad read_file calls.")
                .stringProperty("pattern", "regex pattern", true)
                .stringProperty("path", "Optional workspace-relative file or directory path; defaults to the workspace root", false)
                .stringProperty("include", "Optional file suffix or glob fragment, for example .java, .vue, or Controller", false)
                .intProperty("max_results", "Maximum result count; default 50", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String patternText = ToolSupport.stringArgMulti(args, "", "pattern", "regex", "query");
        if (patternText.isBlank()) return ToolResult.failed("code=PATTERN_REQUIRED\npattern is required");

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException invalidPattern) {
            return ToolResult.failed("code=INVALID_REGEX\n"
                    + "message=" + ToolSupport.limit(invalidPattern.getDescription(), 240));
        }
        SecureWorkspacePath paths = ToolSupport.workspacePaths(context);
        Path searchRoot;
        try {
            searchRoot = resolveSearchRoot(context, args, paths);
        } catch (IllegalArgumentException invalidPath) {
            return invalidSearchPath(invalidPath);
        }

        String include = ToolSupport.stringArg(args, "include", "");
        int max = Math.min(200, Math.max(1, ToolSupport.intArg(args, "max_results", 50)));
        ArrayList<String> hits = new ArrayList<>();
        long[] bytesRead = {0L};
        boolean[] byteBudgetReached = {false};
        Path workspaceRoot = paths.workspaceRoot();

        WorkspaceScanner.ScanResult scan = null;
        if (Files.isDirectory(searchRoot, LinkOption.NOFOLLOW_LINKS)) {
            scan = workspaceScanner.scan(paths, searchRoot, WorkspaceScanner.INTERACTIVE_SEARCH_BUDGET,
                    context.getCancellationToken(), (file, attributes) -> collectMatches(file, attributes, searchRoot,
                            workspaceRoot, pattern, include, max, hits, bytesRead, byteBudgetReached));
        } else if (Files.isRegularFile(searchRoot, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(searchRoot, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            collectMatches(searchRoot, attributes, searchRoot.getParent(), workspaceRoot, pattern, include, max,
                    hits, bytesRead, byteBudgetReached);
        } else {
            return ToolResult.failed("code=SEARCH_PATH_UNSUPPORTED\n"
                    + "next_action=use a regular workspace file or directory");
        }

        String content = hits.isEmpty() ? "No matches." : String.join("\n", hits);
        if (byteBudgetReached[0]) {
            content += "\n\nScan truncated: read byte budget reached (read_bytes=" + bytesRead[0] + ").";
        } else if (scan != null && scan.truncated()) {
            content += "\n\nScan truncated: " + scan.stopReason().name().toLowerCase(Locale.ROOT)
                    + " (visited=" + scan.visitedEntries() + ", candidates=" + scan.candidateFiles()
                    + ", skipped_directories=" + scan.skippedDirectories() + ", elapsed_ms=" + scan.elapsedMillis()
                    + ").";
        }
        return ToolResult.ok(content);
    }

    private Path resolveSearchRoot(AgentContext context, JsonObject args, SecureWorkspacePath paths) {
        String requestedPath = ToolSupport.stringArg(args, "path", "").trim();
        if (requestedPath.isBlank()) {
            return paths.workspaceRoot();
        }
        return ToolSupport.resolve(context, requestedPath);
    }

    private ToolResult invalidSearchPath(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "invalid search path" : exception.getMessage();
        if ("path does not exist".equals(message)) {
            return ToolResult.failed("code=SEARCH_PATH_NOT_FOUND\n"
                    + "next_action=use list_files or glob to locate a workspace path before retrying");
        }
        return ToolResult.failed("code=SEARCH_PATH_INVALID\nmessage=" + ToolSupport.limit(message, 240));
    }

    private boolean collectMatches(Path file, BasicFileAttributes attributes, Path scopeRoot, Path workspaceRoot,
                                   Pattern pattern, String include, int max, ArrayList<String> hits,
                                   long[] bytesRead, boolean[] byteBudgetReached) {
        if (hits.size() >= max || byteBudgetReached[0] || attributes.size() > MAX_FILE_BYTES || isLikelyBinary(file)) {
            return hits.size() < max && !byteBudgetReached[0];
        }
        String scopedRelative = relative(scopeRoot, file);
        if (!include.isBlank() && !scopedRelative.contains(include)) {
            return true;
        }
        if (bytesRead[0] + attributes.size() > MAX_TOTAL_BYTES) {
            byteBudgetReached[0] = true;
            return false;
        }
        bytesRead[0] += attributes.size();
        String relative = relative(workspaceRoot, file);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && hits.size() < max) {
                lineNumber++;
                if (pattern.matcher(line).find()) {
                    hits.add(relative + ":" + lineNumber + ": " + ToolSupport.limit(line.trim(), 240));
                }
            }
        } catch (Exception ignored) {
            // 不可读或非 UTF-8 文件不是 grep 候选，继续扫描其他安全文件。
        }
        return hits.size() < max;
    }

    private String relative(Path root, Path file) {
        if (root == null || file == null) {
            return "";
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (normalizedRoot.equals(normalizedFile)) {
            Path name = normalizedFile.getFileName();
            return name == null ? "" : name.toString().replace('\\', '/');
        }
        return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
    }

    private boolean isLikelyBinary(Path file) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(8192);
            for (byte value : bytes) {
                if (value == 0) return true;
            }
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }
}