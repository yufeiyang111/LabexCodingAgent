package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.ProjectIndexService;
import com.labex.labexagent.run.SubagentDispatchService;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TaskTool
implements AgentTool {
    private final ProjectIndexService projectIndexService;
    private final SubagentDispatchService dispatchService;

    public TaskTool(ProjectIndexService projectIndexService, SubagentDispatchService dispatchService) {
        this.projectIndexService = projectIndexService;
        this.dispatchService = dispatchService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("task")
                .description("Run a focused read-only subagent for research, codebase exploration, or parallel analysis. It returns findings to the main agent and does not modify files.")
                .stringProperty("description", "short 3-8 word task title", true)
                .stringProperty("prompt", "full task for the subagent", false)
                .stringProperty("subagent_type", "general, explore, or scout; default general", false)
                .stringProperty("task_id", "optional previous task id to resume in the caller's context", false)
                .stringProperty("background", "true to run without blocking the parent agent", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String description = ToolSupport.stringArg(args, "description", "").trim();
        String prompt = ToolSupport.stringArgMulti(args, "", "prompt", "task", "question").trim();
        if (prompt.isBlank()) {
            prompt = description;
        }
        if (description.isBlank()) {
            description = ToolSupport.limit(prompt.replaceAll("\\s+", " "), 80);
        }
        if (prompt.isBlank()) {
            return ToolResult.failed("prompt or description is required");
        }

        String subagentType = normalizeSubagentType(ToolSupport.stringArg(args, "subagent_type", "general"));
        String subtaskId = ToolSupport.stringArg(args, "task_id", "");
        if (subtaskId.isBlank()) {
            subtaskId = UUID.randomUUID().toString();
        }

        try {
            String digest = projectIndexService.buildProjectDigest(context.getProject(), prompt);
            String instructions = buildSubagentPrompt(subagentType) + "\n\nSubtask title: " + description
                    + "\n\nSubtask request:\n" + prompt + "\n\nProject digest:\n" + ToolSupport.limit(digest, 16000);
            boolean background = args.has("background") && Boolean.parseBoolean(args.get("background").getAsString());
            var dispatch = dispatchService.dispatch(
                    context.getSessionId(), context.getStudentId(), context.getProject(), context.getConversationId(), context.getTaskId(),
                    subagentType, instructions, null, 4096, "[]", "[]", background);
            if (background) {
                return ToolResult.ok("<task id=\"" + dispatch.subagent().getSubagentId() + "\" state=\"running\" agent=\"" + subagentType + "\">" + escapeXml(description) + "</task>");
            }
            dispatch.completion().join();
            String content = dispatch.subagent().getSummary();
            if (content == null || content.isBlank()) return ToolResult.failed("Subtask returned no content.");
            return ToolResult.ok(formatSubtaskOutput(String.valueOf(dispatch.subagent().getSubagentId()), subagentType, description, content));
        } catch (Exception e) {
            return ToolResult.failed("Subtask failed: " + e.getMessage());
        }
    }

    private String normalizeSubagentType(String value) {
        String normalized = value == null ? "general" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("explore") || normalized.equals("scout")) {
            return normalized;
        }
        return "general";
    }

    private String buildSubagentPrompt(String subagentType) {
        String shared = """
                You are a focused Labex subagent. You work read-only: do not claim that you edited files, ran commands, or changed the workspace.
                Use only the supplied task, parent plan, and project digest. If evidence is insufficient, say exactly what the parent agent should inspect next.
                Return concise Markdown with these sections:
                ## Findings
                ## Relevant Files
                ## Suggested Next Steps
                Include exact file paths when available.
                """;
        return switch (subagentType) {
            case "explore" -> shared + "\nYou specialize in codebase exploration. Prioritize file locations, symbols, routes, and search strategy.";
            case "scout" -> shared + "\nYou specialize in documentation and dependency research. Flag when web_search or web_fetch is needed for external evidence.";
            default -> shared + "\nYou are a general-purpose subagent for independent analysis and decomposition.";
        };
    }

    private String formatSubtaskOutput(String taskId, String subagentType, String description, String content) {
        return "<task id=\"" + taskId + "\" state=\"completed\" agent=\"" + subagentType + "\">\n"
                + "<summary>" + escapeXml(description) + "</summary>\n"
                + "<task_result>\n"
                + ToolSupport.limit(content, 8000)
                + "\n</task_result>\n"
                + "</task>";
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
