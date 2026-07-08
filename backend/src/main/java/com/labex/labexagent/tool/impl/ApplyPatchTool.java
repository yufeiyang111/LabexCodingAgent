package com.labex.labexagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
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
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApplyPatchTool
implements AgentTool {
    private final DiffService diffService;

    public ApplyPatchTool(DiffService diffService) {
        this.diffService = diffService;
    }

    public ToolDefinition definition() {
        Map<String, Object> changeItem = Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string", "description", "\u6587\u4ef6\u8def\u5f84"), "operation", Map.of("type", "string", "description", "\u64cd\u4f5c: create/replace/delete"), "content", Map.of("type", "string", "description", "\u6587\u4ef6\u5185\u5bb9\uff08create\u65f6\u5fc5\u586b\uff09"), "old_string", Map.of("type", "string", "description", "\u65e7\u5185\u5bb9\uff08replace\u65f6\u5fc5\u586b\uff09"), "new_string", Map.of("type", "string", "description", "\u65b0\u5185\u5bb9\uff08replace\u65f6\u5fc5\u586b\uff09")), "required", List.of("path", "operation"));
        return ToolDefinition.builder().name("apply_patch").description("\u6279\u91cf\u521b\u5efa/\u4fee\u6539/\u5220\u9664\u6587\u4ef6\uff0c\u6587\u4ef6\u4f1a\u81ea\u52a8\u5e94\u7528\u5e76\u8bb0\u5f55\u53d8\u66f4\u5386\u53f2\uff0c\u652f\u6301\u56de\u9000\u3002\u9002\u5408\u4e00\u6b21\u63d0\u4ea4\u591a\u4e2a\u6587\u4ef6\u53d8\u66f4\u3002").arrayProperty("changes", "\u53d8\u66f4\u5217\u8868", changeItem, true).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        if (!args.has("changes") || !args.get("changes").isJsonArray()) {
            return ToolResult.failed("changes \u53c2\u6570\u5fc5\u987b\u662f\u6570\u7ec4");
        }
        JsonArray changes = args.getAsJsonArray("changes");
        if (changes.isEmpty()) {
            return ToolResult.failed("changes \u4e0d\u80fd\u4e3a\u7a7a");
        }
        ArrayList<PendingChange> pendingChanges = new ArrayList<PendingChange>();
        StringBuilder combinedDiff = new StringBuilder();
        String firstChangeId = null;
        Path root = context.getWorkspaceRoot();
        block10: for (JsonElement el : changes) {
            Path file;
            String cleaned;
            String operation;
            if (!el.isJsonObject()) continue;
            JsonObject change = el.getAsJsonObject();
            String path = change.has("path") ? change.get("path").getAsString() : "";
            String string = operation = change.has("operation") ? change.get("operation").getAsString() : "create";
            if (path.isEmpty() || (cleaned = ToolSupport.normalizeRelativePath((String)path)).isEmpty() || !(file = root.resolve(cleaned).normalize()).startsWith(root)) continue;
            String beforeContent = "";
            String afterContent = "";
            String changeType = "modify";
            switch (operation) {
                case "create": {
                    afterContent = change.has("content") ? change.get("content").getAsString() : "";
                    changeType = "create";
                    break;
                }
                case "replace": {
                    if (Files.exists(file, new LinkOption[0])) {
                        beforeContent = Files.readString(file);
                    }
                    String oldString = change.has("old_string") ? change.get("old_string").getAsString() : "";
                    String newString = change.has("new_string") ? change.get("new_string").getAsString() : "";
                    afterContent = beforeContent.replace(oldString, newString);
                    break;
                }
                case "delete": {
                    if (Files.exists(file, new LinkOption[0])) {
                        beforeContent = Files.readString(file);
                    }
                    changeType = "delete";
                    break;
                }
                default: {
                    continue block10;
                }
            }
            PendingChange pending = this.diffService.stageAndApply(context.getStudentId(), context.getProject(), context.getConversationId(), context.getTaskId(), cleaned, beforeContent, afterContent, changeType);
            pendingChanges.add(pending);
            combinedDiff.append(pending.getDiff()).append("\n");
            if (firstChangeId != null) continue;
            firstChangeId = pending.getId();
        }
        if (pendingChanges.isEmpty()) {
            return ToolResult.failed("\u6ca1\u6709\u6709\u6548\u7684\u53d8\u66f4");
        }
        return ToolResult.ok((String)("\u5df2\u81ea\u52a8\u5e94\u7528 " + pendingChanges.size() + " \u4e2a\u6587\u4ef6\u53d8\u66f4\uff08\u53ef\u968f\u65f6\u56de\u9000\uff09")).withDiff(combinedDiff.toString()).withPendingChangeId(firstChangeId);
    }
}

