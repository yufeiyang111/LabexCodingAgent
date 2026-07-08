package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.ProjectIndexService;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TaskTool
implements AgentTool {
    private final ProjectIndexService projectIndexService;
    private final AgentModelConfigService modelConfigService;
    private final LlmProviderFactory providerFactory;

    public TaskTool(ProjectIndexService projectIndexService,
                    AgentModelConfigService modelConfigService,
                    LlmProviderFactory providerFactory) {
        this.projectIndexService = projectIndexService;
        this.modelConfigService = modelConfigService;
        this.providerFactory = providerFactory;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("task")
                .description("Run a focused read-only subagent for research, codebase exploration, or parallel analysis. It returns findings to the main agent and does not modify files.")
                .stringProperty("description", "short 3-8 word task title", true)
                .stringProperty("prompt", "full task for the subagent", false)
                .stringProperty("subagent_type", "general, explore, or scout; default general", false)
                .stringProperty("task_id", "optional previous task id to resume in the caller's context", false)
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
            AgentModelConfig modelConfig = modelConfigService.resolveForStudent(context.getStudentId(), null);
            LlmProvider provider = providerFactory.resolveProvider(modelConfig);
            LlmProvider.LlmConfig baseConfig = providerFactory.buildConfig(modelConfig);
            LlmProvider.LlmConfig subConfig = new LlmProvider.LlmConfig(
                    baseConfig.apiKey(),
                    baseConfig.baseUrl(),
                    baseConfig.modelName(),
                    Math.min(baseConfig.maxTokens() == null ? 4096 : baseConfig.maxTokens(), 4096),
                    baseConfig.temperature()
            );

            String digest = projectIndexService.buildProjectDigest(context.getProject(), prompt);
            String systemPrompt = buildSubagentPrompt(subagentType);
            String userPrompt = """
                    Parent session: %s
                    Parent mode: %s
                    Workspace root: %s

                    Current parent plan:
                    %s

                    Subtask title:
                    %s

                    Subtask request:
                    %s

                    Project digest and related snippets:
                    %s
                    """.formatted(
                    context.getSessionId(),
                    context.getMode(),
                    context.getWorkspaceRoot(),
                    context.getPlanSummary().isBlank() ? "(none)" : context.getPlanSummary(),
                    description,
                    prompt,
                    ToolSupport.limit(digest, 16000)
            );

            Map<String, Object> response = provider.chatWithTools(
                    systemPrompt,
                    List.of(Map.of("role", "user", "content", userPrompt)),
                    List.of(),
                    subConfig
            );
            String type = String.valueOf(response.getOrDefault("type", ""));
            if ("error".equals(type)) {
                return ToolResult.failed("Subtask failed: " + response.getOrDefault("message", "unknown error"));
            }
            String content = String.valueOf(response.getOrDefault("content", "")).trim();
            if (content.isBlank()) {
                return ToolResult.failed("Subtask returned no content.");
            }
            return ToolResult.ok(formatSubtaskOutput(subtaskId, subagentType, description, content));
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
