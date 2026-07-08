package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RetrieveContextTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("retrieve_context").description("\u6309\u4efb\u52a1\u63cf\u8ff0\u68c0\u7d22\u9879\u76ee\u7ea7\u76f8\u5173\u6587\u4ef6\u548c\u4ee3\u7801\u7247\u6bb5").stringProperty("query", "\u641c\u7d22\u5185\u5bb9", true).intProperty("limit", "\u6700\u5927\u8fd4\u56de\u7ed3\u679c\u6570\uff08\u9ed8\u8ba45\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String query;
        String string = query = args.has("query") ? args.get("query").getAsString() : "";
        if (query.isEmpty()) {
            return ToolResult.failed("query is required");
        }
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 5;
        Path root = context.getWorkspaceRoot();
        ArrayList<String> results = new ArrayList<>();
        this.searchInDirectory(root, root, query.toLowerCase(), results, limit);
        if (results.isEmpty()) {
            return ToolResult.ok((String)("\u672a\u627e\u5230\u4e0e '" + query + "' \u76f8\u5173\u7684\u4ee3\u7801"));
        }
        return ToolResult.ok((String)String.join("\n\n", results));
    }

    private void searchInDirectory(Path dir, Path root, String query, List<String> results, int limit) {
        if (results.size() >= limit) {
            return;
        }
        try {
            Files.list(dir).filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith(".") && !name.equals("node_modules") && !name.equals("target") && !name.equals("build");
            }).forEach(p -> {
                if (results.size() >= limit) {
                    return;
                }
                if (Files.isDirectory(p, new LinkOption[0])) {
                    this.searchInDirectory(p, root, query, results, limit);
                } else if (Files.isRegularFile(p, new LinkOption[0])) {
                    try {
                        String content = Files.readString(p);
                        String lowerContent = content.toLowerCase();
                        String relativePath = root.relativize((Path)p).toString();
                        if (lowerContent.contains(query)) {
                            String[] lines = content.split("\n");
                            StringBuilder context = new StringBuilder();
                            context.append("\u6587\u4ef6: ").append(relativePath).append("\n");
                            for (int i = 0; i < lines.length; ++i) {
                                if (!lines[i].toLowerCase().contains(query)) continue;
                                int start = Math.max(0, i - 2);
                                int end = Math.min(lines.length, i + 3);
                                for (int j = start; j < end; ++j) {
                                    context.append(j + 1).append(": ").append(lines[j]).append("\n");
                                }
                                context.append("---\n");
                                break;
                            }
                            results.add(context.toString());
                        }
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
            });
        }
        catch (Exception exception) {
            // empty catch block
        }
    }
}

