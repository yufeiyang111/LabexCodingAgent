package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

// 已禁用：与 GrepTool 功能重复，grep 支持正则更强大
// @Component
public class SearchCodeTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("search_code").description("\u5728\u6587\u4ef6\u5185\u5bb9\u4e2d\u641c\u7d22\u6307\u5b9a\u6587\u672c\uff0c\u8fd4\u56de\u6587\u4ef6\u548c\u884c\u53f7").stringProperty("query", "\u641c\u7d22\u5173\u952e\u8bcd", true).stringProperty("include_pattern", "\u6587\u4ef6\u8fc7\u6ee4\u6a21\u5f0f\uff08\u5982 '*.java'\uff09", false).intProperty("max_results", "\u6700\u5927\u8fd4\u56de\u7ed3\u679c\u6570\uff08\u9ed8\u8ba420\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String query = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"query", "search", "keyword", "text"});
        if (query.isEmpty()) {
            return ToolResult.failed("query is required");
        }
        int maxResults = args.has("max_results") ? args.get("max_results").getAsInt() : 20;
        Path root = context.getWorkspaceRoot();
        ArrayList<String> results = new ArrayList<>();
        this.searchInDirectory(root, root, query, results, maxResults);
        if (results.isEmpty()) {
            return ToolResult.ok((String)("\u672a\u627e\u5230\u5339\u914d\u7ed3\u679c: " + query));
        }
        return ToolResult.ok((String)String.join("\n", results));
    }

    private void searchInDirectory(Path dir, Path root, String query, List<String> results, int maxResults) {
        if (results.size() >= maxResults) {
            return;
        }
        try {
            Files.list(dir).filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith(".") && !name.equals("node_modules") && !name.equals("target") && !name.equals("build");
            }).forEach(p -> {
                if (results.size() >= maxResults) {
                    return;
                }
                if (Files.isDirectory(p, new LinkOption[0])) {
                    this.searchInDirectory(p, root, query, results, maxResults);
                } else if (Files.isRegularFile(p, new LinkOption[0])) {
                    try {
                        String content = Files.readString(p);
                        String[] lines = content.split("\n");
                        String relativePath = root.relativize((Path)p).toString();
                        for (int i = 0; i < lines.length; ++i) {
                            if (results.size() >= maxResults) {
                                return;
                            }
                            if (!lines[i].contains(query)) continue;
                            results.add(relativePath + ":" + (i + 1) + ": " + lines[i].trim());
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

