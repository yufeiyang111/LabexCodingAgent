package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class GlobTool
implements AgentTool {
    private static final Set<String> IGNORED = Set.of(".git", "node_modules", "dist", "build", "target", "__pycache__", ".idea", ".vscode");

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("glob").description("\u6309 glob \u6a21\u5f0f\u67e5\u627e\u6587\u4ef6\u8def\u5f84\u3002\u9002\u5408\u5148\u5b9a\u4f4d\u6587\u4ef6\uff0c\u907f\u514d\u8bfb\u53d6\u6574\u4e2a\u9879\u76ee\u3002").stringProperty("pattern", "glob pattern, e.g. **/*.java or frontend/src/**/*.vue", true).intProperty("max_results", "\u6700\u5927\u8fd4\u56de\u6761\u6570\uff0c\u9ed8\u8ba480", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String pattern = ToolSupport.stringArgMulti((JsonObject)args, "", (String[])new String[]{"pattern", "glob"});
        if (pattern.isBlank()) {
            return ToolResult.failed("pattern is required");
        }
        int max = Math.min(300, Math.max(1, ToolSupport.intArg((JsonObject)args, "max_results", 80)));
        Path root = context.getWorkspaceRoot();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace('/', File.separatorChar));
        ArrayList<String> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, new FileVisitOption[0]);){
            stream.filter(x$0 -> Files.isRegularFile(x$0, new LinkOption[0])).filter(path -> !this.isIgnored(root, path)).filter(path -> matcher.matches(root.relativize((Path)path)) || matcher.matches(path.getFileName())).sorted(Comparator.comparing(path -> root.relativize((Path)path).toString())).limit(max).forEach(path -> hits.add(root.relativize((Path)path).toString().replace('\\', '/')));
        }
        return ToolResult.ok((String)(hits.isEmpty() ? "No files matched." : String.join("\n", hits)));
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

