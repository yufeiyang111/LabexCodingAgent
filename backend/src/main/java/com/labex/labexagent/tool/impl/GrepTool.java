package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.WorkspaceScanner;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
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
        return ToolDefinition.builder().name("grep").description("用正则搜索代码内容，返回路径、行号和短上下文。适合替代大范围 read_file。")
                .stringProperty("pattern", "regex pattern", true)
                .stringProperty("include", "可选文件后缀或 glob 片段，如 .java、.vue、Controller", false)
                .intProperty("max_results", "最大返回条数，默认50", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String patternText = ToolSupport.stringArgMulti(args, "", "pattern", "regex", "query");
        if (patternText.isBlank()) return ToolResult.failed("pattern is required");

        String include = ToolSupport.stringArg(args, "include", "");
        int max = Math.min(200, Math.max(1, ToolSupport.intArg(args, "max_results", 50)));
        Pattern pattern = Pattern.compile(patternText, Pattern.CASE_INSENSITIVE);
        Path root = ToolSupport.workspacePaths(context).workspaceRoot();
        ArrayList<String> hits = new ArrayList<>();
        long[] bytesRead = {0L};
        boolean[] byteBudgetReached = {false};
        WorkspaceScanner.ScanResult scan = workspaceScanner.scan(ToolSupport.workspacePaths(context),
                WorkspaceScanner.INTERACTIVE_SEARCH_BUDGET, context.getCancellationToken(), (file, attributes) -> {
                    if (hits.size() >= max || byteBudgetReached[0] || attributes.size() > MAX_FILE_BYTES
                            || (!include.isBlank() && !root.relativize(file).toString().replace('\\', '/').contains(include))
                            || isLikelyBinary(file)) {
                        return hits.size() < max && !byteBudgetReached[0];
                    }
                    if (bytesRead[0] + attributes.size() > MAX_TOTAL_BYTES) {
                        byteBudgetReached[0] = true;
                        return false;
                    }
                    bytesRead[0] += attributes.size();
                    String relative = root.relativize(file).toString().replace('\\', '/');
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
                        // Unreadable or non-UTF-8 files are not eligible grep candidates.
                    }
                    return hits.size() < max;
                });

        String content = hits.isEmpty() ? "No matches." : String.join("\n", hits);
        if (byteBudgetReached[0]) {
            content += "\n\nScan truncated: read byte budget reached (read_bytes=" + bytesRead[0] + ").";
        } else if (scan.truncated()) {
            content += "\n\nScan truncated: " + scan.stopReason().name().toLowerCase(java.util.Locale.ROOT)
                    + " (visited=" + scan.visitedEntries() + ", candidates=" + scan.candidateFiles()
                    + ", skipped_directories=" + scan.skippedDirectories() + ", elapsed_ms=" + scan.elapsedMillis() + ").";
        }
        return ToolResult.ok(content);
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
