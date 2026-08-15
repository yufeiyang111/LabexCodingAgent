package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.WorkspaceScanner;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GlobTool implements AgentTool {
    private final WorkspaceScanner workspaceScanner;

    public GlobTool(WorkspaceScanner workspaceScanner) {
        this.workspaceScanner = workspaceScanner;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("glob").description("Find paths by glob pattern. Use it to locate candidate files before reading and avoid broad project reads.")
                .stringProperty("pattern", "glob pattern, e.g. **/*.java or frontend/src/**/*.vue", true)
                .intProperty("max_results", "Maximum result count; default 80", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String pattern = ToolSupport.stringArgMulti(args, "", "pattern", "glob");
        if (pattern.isBlank()) return ToolResult.failed("pattern is required");

        int max = Math.min(300, Math.max(1, ToolSupport.intArg(args, "max_results", 80)));
        Path root = ToolSupport.workspacePaths(context).workspaceRoot();
        List<Pattern> patterns = globPatterns(pattern);
        ArrayList<String> hits = new ArrayList<>();
        WorkspaceScanner.ScanResult scan = workspaceScanner.scan(ToolSupport.workspacePaths(context),
                WorkspaceScanner.INTERACTIVE_SEARCH_BUDGET, context.getCancellationToken(), (file, attributes) -> {
                    Path relative = root.relativize(file);
                    if (matches(patterns, relative.toString().replace('\\', '/')) && hits.size() < max) {
                        hits.add(relative.toString().replace('\\', '/'));
                    }
                    return hits.size() < max;
                });

        String content = hits.isEmpty() ? "No files matched." : String.join("\n", hits);
        return ToolResult.ok(appendScanSummary(content, scan));
    }

    private List<Pattern> globPatterns(String pattern) {
        return expandBraceGroups(pattern).stream()
                .map(this::globPattern)
                .toList();
    }

    private boolean matches(List<Pattern> patterns, String relativePath) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(relativePath).matches());
    }

    private Pattern globPattern(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '*') {
                boolean globStar = index + 1 < pattern.length() && pattern.charAt(index + 1) == '*';
                if (globStar) {
                    index++;
                    if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '/') {
                        regex.append("(?:.*/)?");
                        index++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (character == '?') {
                regex.append("[^/]");
            } else if (character == '/') {
                regex.append('/');
            } else {
                if ("\\.[]()^$+|".indexOf(character) >= 0) regex.append('\\');
                regex.append(character);
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private List<String> expandBraceGroups(String pattern) {
        int open = pattern.indexOf('{');
        if (open < 0) return List.of(pattern);
        int close = pattern.indexOf('}', open + 1);
        if (close < 0) return List.of(pattern);

        String prefix = pattern.substring(0, open);
        String suffix = pattern.substring(close + 1);
        return java.util.Arrays.stream(pattern.substring(open + 1, close).split(","))
                .filter(part -> !part.isBlank())
                .flatMap(part -> expandBraceGroups(prefix + part.trim() + suffix).stream())
                .toList();
    }

    private String appendScanSummary(String content, WorkspaceScanner.ScanResult scan) {
        if (!scan.truncated()) return content;
        return content + "\n\nScan truncated: " + scan.stopReason().name().toLowerCase(java.util.Locale.ROOT)
                + " (visited=" + scan.visitedEntries() + ", candidates=" + scan.candidateFiles()
                + ", skipped_directories=" + scan.skippedDirectories() + ", elapsed_ms=" + scan.elapsedMillis() + ").";
    }
}
