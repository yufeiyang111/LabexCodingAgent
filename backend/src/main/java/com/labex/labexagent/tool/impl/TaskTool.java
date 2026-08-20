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
                .description("Launch a specialized subagent for complex, multistep tasks or deep read-only codebase research.\n"
                        + "Available subagent types:\n"
                        + "- explore: Fast agent specialized for exploring codebases. Quickly find files by patterns, search code for keywords/symbols, or understand codebase architecture.\n"
                        + "- scout: Specialized in documentation, dependency research, and API/protocol analysis.\n"
                        + "- general: General-purpose agent for multi-step reasoning, independent analysis, and decomposition.")
                .stringProperty("name", "custom descriptive role name for the subagent, e.g. 'Frontend Architecture Explorer' or 'API Protocol Scout'", false)
                .stringProperty("description", "short 3-8 word task summary", true)
                .stringProperty("prompt", "detailed actionable task description and instructions for the subagent", false)
                .stringProperty("subagent_type", "type of specialized agent to use: 'explore', 'scout', or 'general' (default: 'general')", false)
                .booleanProperty("background", "run asynchronously without blocking the parent agent (default: false)", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String name = ToolSupport.stringArgMulti(args, "", "name", "agent_name", "subagent_name", "role").trim();
        String description = ToolSupport.stringArg(args, "description", "").trim();
        String prompt = ToolSupport.stringArgMulti(args, "", "prompt", "task", "question").trim();
        if (prompt.isBlank()) {
            prompt = description;
        }
        if (description.isBlank()) {
            description = name.isBlank() ? ToolSupport.limit(prompt.replaceAll("\\s+", " "), 80) : name;
        }
        if (name.isBlank()) {
            name = description;
        }
        if (prompt.isBlank()) {
            return ToolResult.failed("prompt or description is required");
        }

        String subagentType = normalizeSubagentType(ToolSupport.stringArg(args, "subagent_type", "general"));
        try {
            String digest = projectIndexService.buildProjectDigest(context.getProject(), prompt);
            String instructions = buildSubagentPrompt(subagentType) + "\n\nSubagent name: " + name
                    + "\n\nSubtask title: " + description
                    + "\n\nSubtask request:\n" + prompt + "\n\nProject digest:\n" + ToolSupport.limit(digest, 16000);
            boolean background = args.has("background") && !args.get("background").isJsonNull()
                    && args.get("background").getAsBoolean();
            String identity = name.isBlank() || name.equalsIgnoreCase(subagentType)
                    ? subagentType : (name + " (" + subagentType + ")");
            var dispatch = dispatchService.dispatch(
                    context.getSessionId(), context.getStudentId(), context.getProject(), context.getConversationId(), context.getTaskId(),
                    identity, instructions, null, 4096, "[]", "[]", background);
            if (background) {
                return ToolResult.ok("<task id=\"" + dispatch.subagent().getSubagentId() + "\" name=\"" + escapeXml(name) + "\" state=\"running\" agent=\"" + subagentType + "\">" + escapeXml(description) + "</task>");
            }
            dispatch.completion().join();
            String content = dispatch.subagent().getSummary();
            if (content == null || content.isBlank()) return ToolResult.failed("Subtask returned no content.");
            return ToolResult.ok(formatSubtaskOutput(String.valueOf(dispatch.subagent().getSubagentId()), name, subagentType, description, content));
        } catch (Exception e) {
            String detail = e.getMessage();
            return ToolResult.failed("code=SUBTASK_FAILED\n"
                    + "error_type=" + e.getClass().getSimpleName() + "\n"
                    + "message=" + (detail == null || detail.isBlank()
                    ? "subtask dispatch failed"
                    : ToolSupport.limit(detail, 512)));
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
                You are a focused, capability-isolated Labex subagent.
                Your job is to autonomously perform the assigned task and report structured findings back to the main agent.

                Guidelines:
                - Work read-only: do not create or edit workspace files, and do not execute destructive commands.
                - Structure your final response clearly using the following sections:
                  ## Summary
                  - [1-2 sentence high-level finding]
                  ## Technical Findings & Details
                  - [key logic, architecture, data flow, or protocol facts]
                  ## Relevant Files & Locations
                  - [file paths and line references if known]
                  ## Next Steps & Recommendations
                  - [actionable next steps for the parent agent]
                - Return clean relative file paths and concise, high-signal information.
                """;
        return switch (subagentType) {
            case "explore" -> shared + """

                You are a Codebase Exploration Specialist (explore):
                - Rapidly locate candidate files using glob patterns and structure matching.
                - Search code, symbols, class definitions, and endpoints with regex / grep.
                - Read and analyze file contents to understand existing patterns and conventions.
                """;
            case "scout" -> shared + """

                You are a Documentation & Protocol Scout (scout):
                - Specialize in library dependencies, configuration formats, and protocol reverse-engineering.
                - Synthesize external specs and API patterns to provide exact integration steps.
                """;
            default -> shared + """

                You are a General-purpose Subagent (general):
                - Specialize in autonomous multistep reasoning, independent task decomposition, and verification planning.
                """;
        };
    }

    private String formatSubtaskOutput(String taskId, String name, String subagentType, String description, String content) {
        return "<task id=\"" + taskId + "\" name=\"" + escapeXml(name) + "\" state=\"completed\" agent=\"" + subagentType + "\">\n"
                + "<name>" + escapeXml(name) + "</name>\n"
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
