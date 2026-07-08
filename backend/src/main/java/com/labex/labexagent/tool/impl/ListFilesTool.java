package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ListFilesTool
implements AgentTool {
    private final StudentProjectService studentProjectService;

    public ListFilesTool(StudentProjectService studentProjectService) {
        this.studentProjectService = studentProjectService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("list_files").description("\u9012\u5f52\u5217\u51fa\u76ee\u5f55\u6811\uff0c\u5ffd\u7565\u5e38\u89c1\u6784\u5efa/\u4f9d\u8d56\u76ee\u5f55").stringProperty("path", "\u76ee\u5f55\u8def\u5f84\uff08\u9ed8\u8ba4\u5f53\u524d\u9879\u76ee\u6839\u76ee\u5f55\uff09", false).intProperty("max_depth", "\u6700\u5927\u9012\u5f52\u6df1\u5ea6\uff08\u9ed8\u8ba43\uff09", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        Path target;
        String path = ToolSupport.normalizeRelativePath((String)(args.has("path") ? args.get("path").getAsString() : "."));
        int maxDepth = args.has("max_depth") ? args.get("max_depth").getAsInt() : 3;
        Path root = context.getWorkspaceRoot();
        Path path2 = target = path.isEmpty() || path.equals(".") ? root : root.resolve(path).normalize();
        if (!target.startsWith(root)) {
            return ToolResult.failed("Unsafe path");
        }
        if (!Files.exists(target, new LinkOption[0])) {
            return ToolResult.failed((String)("Path does not exist: " + path));
        }
        if (Files.isRegularFile(target, new LinkOption[0])) {
            return ToolResult.ok((String)target.getFileName().toString());
        }
        String tree = this.buildTree(target, root, 0, maxDepth);
        return ToolResult.ok((String)tree);
    }

    private String buildTree(Path dir, Path root, int depth, int maxDepth) {
        if (depth >= maxDepth) {
            return "";
        }
        try {
            return Files.list(dir).filter(p -> {
                String name = p.getFileName().toString();
                return !name.startsWith(".") && !name.equals("node_modules") && !name.equals("target") && !name.equals("build") && !name.equals("dist");
            }).sorted().map(p -> {
                String prefix = "  ".repeat(depth);
                String name = p.getFileName().toString();
                if (Files.isDirectory(p, new LinkOption[0])) {
                    String children = this.buildTree(p, root, depth + 1, maxDepth);
                    return prefix + "[D] " + name + "/\n" + children;
                }
                return prefix + "[F] " + name;
            }).collect(Collectors.joining("\n"));
        }
        catch (Exception e) {
            return "";
        }
    }
}

