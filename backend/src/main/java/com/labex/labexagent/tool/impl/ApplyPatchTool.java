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
import com.labex.labexagent.workspace.WorkspaceOperationIdentity;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApplyPatchTool implements AgentTool {
    private static final int MAX_CHANGES = 40;
    private static final int MAX_TOTAL_CONTENT_CHARS = 4_000_000;
    private static final int MAX_DIFF_CHARS = 120_000;

    private final DiffService diffService;

    public ApplyPatchTool(DiffService diffService) {
        this.diffService = diffService;
    }

    public ToolDefinition definition() {
        Map<String, Object> changeItem = Map.of("type", "object", "properties", Map.of(
                "path", Map.of("type", "string", "description", "Workspace-relative file path"),
                "operation", Map.of("type", "string", "description", "Operation: replace an exact text block or delete an existing file"),
                "old_string", Map.of("type", "string", "description", "Exact existing text for replace; it must match once and preserve surrounding file content"),
                "new_string", Map.of("type", "string", "description", "Replacement text for replace")),
                "required", List.of("path", "operation"));
        return ToolDefinition.builder().name("apply_patch")
                .description("Apply precise contextual replacements or delete existing files, including a batch of related changes. Use write_file to create a file or intentionally replace an entire file.")
                .arrayProperty("changes", "Existing-file patch operations", changeItem, true).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        if (!args.has("changes") || !args.get("changes").isJsonArray()) {
            return ToolResult.failed("changes 参数必须是数组");
        }
        JsonArray changes = args.getAsJsonArray("changes");
        if (changes.isEmpty()) {
            return ToolResult.failed("changes 不能为空");
        }
        if (changes.size() > MAX_CHANGES) {
            return ToolResult.failed("Too many changes (max_changes=" + MAX_CHANGES + ")");
        }

        List<DiffService.ChangeRequest> requests = new ArrayList<>();
        int totalContentChars = 0;
        try {
            for (JsonElement element : changes) {
                if (!element.isJsonObject()) {
                    return ToolResult.failed("Each change must be an object");
                }
                PreparedChange prepared = prepareChange(context, element.getAsJsonObject());
                totalContentChars += prepared.beforeContent().length() + prepared.afterContent().length();
                if (totalContentChars > MAX_TOTAL_CONTENT_CHARS) {
                    return ToolResult.failed("Patch content is too large");
                }
                requests.add(new DiffService.ChangeRequest(prepared.relativePath(), prepared.beforeContent(),
                        prepared.afterContent(), prepared.changeType()));
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(e.getMessage());
        }

        List<PendingChange> pendingChanges = this.diffService.stageAndApplyBatchDeferred(context.getStudentId(), context.getProject(),
                context.getConversationId(), context.getTaskId(), requests);
        StringBuilder combinedDiff = new StringBuilder();
        for (PendingChange pending : pendingChanges) {
            appendLimited(combinedDiff, pending.getDiff());
        }
        String firstChangeId = pendingChanges.isEmpty() ? null : pendingChanges.get(0).getId();
        DiffService.ApplyTelemetry telemetry = this.diffService.peekLastApplyTelemetry();
        Map<String, Object> workspaceVerification = telemetry == null ? Map.of() : telemetry.workspaceVerification();
        return ToolResult.ok("已自动应用 " + pendingChanges.size() + " 个文件变更（可随时回退）")
                .withDiff(combinedDiff.toString()).withPendingChangeId(firstChangeId)
                .withWorkspaceChangeEvidence(
                        WorkspaceOperationIdentity.forContext(context, context.getWorkspaceRoot(),
                                requests.stream().map(DiffService.ChangeRequest::relativePath).toList()),
                        pendingChanges.stream().map(PendingChange::getId).toList(),
                        telemetry == null ? Map.of() : telemetry.workspaceMutation())
                .withWorkspaceVerification(workspaceVerification);
    }

    private PreparedChange prepareChange(AgentContext context, JsonObject change) throws Exception {
        String path = stringValue(change, "path");
        String operation = inferredOperation(change);
        String cleaned = ToolSupport.normalizeRelativePath(path);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Unsafe file path");
        }
        if ("create".equals(operation)) {
            throw new IllegalArgumentException("operation create is not supported; use write_file to create a file");
        }
        if (!"replace".equals(operation) && !"delete".equals(operation)) {
            throw new IllegalArgumentException("Unsupported patch operation: " + operation);
        }
        Path file = ToolSupport.resolve(context, cleaned);
        String beforeContent = readExistingText(file);
        return switch (operation) {
            case "delete" -> new PreparedChange(cleaned, beforeContent, "", "delete");
            case "replace" -> replaceChange(cleaned, beforeContent, stringValue(change, "old_string"),
                    stringValue(change, "new_string"));
            default -> throw new IllegalArgumentException("Unsupported patch operation: " + operation);
        };
    }

    private PreparedChange replaceChange(String path, String beforeContent, String oldString, String newString) {
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("old_string must not be empty");
        }
        int firstMatch = beforeContent.indexOf(oldString);
        if (firstMatch < 0) {
            throw new IllegalArgumentException("old_string was not found: " + path);
        }
        if (beforeContent.indexOf(oldString, firstMatch + oldString.length()) >= 0) {
            throw new IllegalArgumentException("old_string must match exactly once: " + path);
        }
        if (oldString.equals(beforeContent)) {
            throw new IllegalArgumentException("replace must preserve surrounding content; use write_file for an intentional full-file replacement: " + path);
        }
        String afterContent = beforeContent.substring(0, firstMatch) + newString
                + beforeContent.substring(firstMatch + oldString.length());
        return new PreparedChange(path, beforeContent, requireEditableContent(afterContent), "modify");
    }

    private String requireEditableContent(String content) {
        ToolSupport.requireEditableTextContent(content);
        return content;
    }

    private String readExistingText(Path file) throws Exception {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            return ToolSupport.readEditableText(file);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            if (message != null && message.startsWith("File target ")) {
                message = "Patch target " + message.substring("File target ".length());
            }
            throw new IllegalArgumentException(message);
        }
    }

    private String inferredOperation(JsonObject change) {
        String explicit = stringValue(change, "operation");
        if (explicit.isBlank()) {
            throw new IllegalArgumentException("operation is required");
        }
        return explicit;
    }

    private String stringValue(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private void appendLimited(StringBuilder builder, String value) {
        if (value == null || value.isEmpty() || builder.length() >= MAX_DIFF_CHARS) {
            return;
        }
        int remaining = MAX_DIFF_CHARS - builder.length();
        if (value.length() <= remaining) {
            builder.append(value).append('\n');
        } else {
            builder.append(value, 0, remaining).append("\n...[truncated]");
        }
    }

    private record PreparedChange(String relativePath, String beforeContent, String afterContent, String changeType) {
    }
}
