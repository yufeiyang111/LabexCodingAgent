package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TodoWriteTool implements AgentTool {
    private static final int MAX_ITEMS = 30;
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*(?:(?:[-*+])|(?:\\d+[.)]))\\s+(?:\\[([xX ])]\\s*)?(.*?)\\s*$");
    private static final Pattern BARE_CHECKBOX = Pattern.compile("^\\s*\\[([xX ])]\\s*(.*?)\\s*$");

    private final AgentRunPlanService planService;

    public TodoWriteTool(AgentRunPlanService planService) {
        this.planService = planService;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("todo_write")
                .description("用 Markdown 或纯文本整体替换当前 task 的持久化短 Todo 列表。")
                .stringProperty("todos", "Markdown or plain text todo list", true)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String todos = ToolSupport.stringArg(args, "todos", "");
        if (todos.isBlank()) {
            return ToolResult.failed("todos is required");
        }
        List<AgentRunPlanService.PlanDraft> drafts = parse(todos);
        if (drafts.isEmpty()) {
            return ToolResult.failed("todos must contain at least one non-empty item");
        }
        if (drafts.size() > MAX_ITEMS) {
            return ToolResult.failed("todos may contain at most " + MAX_ITEMS + " items");
        }
        AgentRunPlanService.Projection projection = planService.replace(
                context.getTaskId(), context.getExecutionEpoch(), drafts, "todo_write");
        projection.applyTo(context);
        return ToolResult.ok("Current durable todo list:\n" + context.getPlanSummary());
    }

    private List<AgentRunPlanService.PlanDraft> parse(String todos) {
        List<AgentRunPlanService.PlanDraft> drafts = new ArrayList<>();
        for (String rawLine : todos.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isBlank()) continue;
            boolean completed = false;
            String title = line;
            Matcher listItem = LIST_ITEM.matcher(line);
            if (listItem.matches()) {
                completed = listItem.group(1) != null && !listItem.group(1).isBlank();
                title = listItem.group(2).strip();
            } else {
                Matcher checkbox = BARE_CHECKBOX.matcher(line);
                if (checkbox.matches()) {
                    completed = !checkbox.group(1).isBlank();
                    title = checkbox.group(2).strip();
                }
            }
            if (!title.isBlank()) {
                drafts.add(new AgentRunPlanService.PlanDraft(title, "", completed));
                if (drafts.size() > MAX_ITEMS) return drafts;
            }
        }
        return List.copyOf(drafts);
    }
}
