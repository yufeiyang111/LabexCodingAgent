package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class GrepTool
implements AgentTool {
    private static final Set<String> IGNORED = Set.of(".git", "node_modules", "dist", "build", "target", "__pycache__", ".idea", ".vscode");

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("grep").description("\u7528\u6b63\u5219\u641c\u7d22\u4ee3\u7801\u5185\u5bb9\uff0c\u8fd4\u56de\u8def\u5f84\u3001\u884c\u53f7\u548c\u77ed\u4e0a\u4e0b\u6587\u3002\u9002\u5408\u66ff\u4ee3\u5927\u8303\u56f4 read_file\u3002").stringProperty("pattern", "regex pattern", true).stringProperty("include", "\u53ef\u9009\u6587\u4ef6\u540e\u7f00\u6216 glob \u7247\u6bb5\uff0c\u5982 .java\u3001.vue\u3001Controller", false).intProperty("max_results", "\u6700\u5927\u8fd4\u56de\u6761\u6570\uff0c\u9ed8\u8ba450", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String patternText = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"pattern", "regex", "query"});
        if (patternText.isBlank()) {
            return ToolResult.failed("pattern is required");
        }
        String include = ToolSupport.stringArg((JsonObject)args, "include", "");
        int max = Math.min(200, Math.max(1, ToolSupport.intArg((JsonObject)args, "max_results", 50)));
        Pattern pattern = Pattern.compile(patternText, 2);
        Path root = context.getWorkspaceRoot();
        ArrayList<String> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, new FileVisitOption[0]);){
            for (Path file : stream.filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).toList()) {
                List<String> lines;
                if (hits.size() >= max) {
                    break;
                }
                if (this.isIgnored(root, file)) continue;
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!include.isBlank() && !relative.contains(include) || ToolSupport.isLikelyBinary((Path)file)) continue;
                try {
                    lines = Files.readAllLines(file);
                }
                catch (Exception ignored) {
                    continue;
                }
                for (int i = 0; i < lines.size() && hits.size() < max; ++i) {
                    String line = lines.get(i);
                    if (!pattern.matcher(line).find()) continue;
                    hits.add(relative + ":" + (i + 1) + ": " + ToolSupport.limit(line.trim(), 240));
                }
            }
        }
        return ToolResult.ok(hits.isEmpty() ? "No matches." : String.join("\n", hits));
    }

    private boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (!IGNORED.contains(part.toString())) continue;
            return true;
        }
        return false;
    }
}

