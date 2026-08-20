package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.run.ExecutionFence;
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
                .description("Replace the current task durable short todo list with Markdown or plain text.")
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
        ExecutionFence fence = requireActiveFence(context);
        AgentRunPlanService.Projection projection = planService.replace(
                fence, context.getTaskId(), context.getExecutionEpoch(), drafts, "todo_write");
        projection.applyTo(context);
        return ToolResult.ok("Current durable todo list:\n" + context.getPlanSummary());
    }

    /** 执行者必须携带其 lease 派生 fence 才能写计划；缺失时以 typed failure fail closed。 */
    private ExecutionFence requireActiveFence(AgentContext context) {
        ExecutionFence fence = context == null ? null : context.getExecutionFence();
        if (fence == null) {
            throw new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                    AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.INVALID_FENCE);
        }
        return fence;
    }

    private List<AgentRunPlanService.PlanDraft> parse(String todos) {
        List<AgentRunPlanService.PlanDraft> drafts = new ArrayList<>();
        for (String rawLine : todos.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isBlank() || line.startsWith("#")) continue;
            boolean completed = false;
            String title = line;

            // Check for completed prefix or checkbox
            if (line.startsWith("✅") || line.startsWith("☑") || line.startsWith("✓") || line.startsWith("✔")
                    || line.startsWith("[已完成]") || line.startsWith("- [x]") || line.startsWith("- [X]")
                    || line.startsWith("* [x]") || line.startsWith("* [X]") || line.startsWith("[x]") || line.startsWith("[X]")) {
                completed = true;
            }

            Matcher listItem = LIST_ITEM.matcher(line);
            if (listItem.matches()) {
                if (!completed && listItem.group(1) != null && !listItem.group(1).isBlank()) {
                    completed = true;
                }
                title = listItem.group(2) == null || listItem.group(2).isBlank() ? line : listItem.group(2).strip();
            } else {
                Matcher checkbox = BARE_CHECKBOX.matcher(line);
                if (checkbox.matches()) {
                    if (!completed && checkbox.group(1) != null && !checkbox.group(1).isBlank()) {
                        completed = true;
                    }
                    title = checkbox.group(2) == null || checkbox.group(2).isBlank() ? line : checkbox.group(2).strip();
                }
            }

            // Strip leading bullet/checkbox/emoji tags from title
            title = title.replaceAll("^[\\-*\t+0-9.)\\[\\]xX✅☑✓✔🔄⏳👉⬜已完成进行中当前待办:\\s]+", "").strip();
            if (title.isBlank()) {
                title = line.replaceAll("^[\\s#\\-*+0-9.)]+", "").strip();
            }

            if (!title.isBlank()) {
                drafts.add(new AgentRunPlanService.PlanDraft(title, "", completed));
                if (drafts.size() > MAX_ITEMS) return drafts;
            }
        }
        return List.copyOf(drafts);
    }
}
