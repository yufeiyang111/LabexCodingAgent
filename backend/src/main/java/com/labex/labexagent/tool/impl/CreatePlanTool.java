package com.labex.labexagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CreatePlanTool implements AgentTool {
    private final AgentRunPlanService planService;

    public CreatePlanTool(AgentRunPlanService planService) {
        this.planService = planService;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> taskItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string", "description", "任务标题"),
                        "description", Map.of("type", "string", "description", "任务描述")),
                "required", List.of("title"));
        return ToolDefinition.builder()
                .name("create_plan")
                .description("创建或管理当前 Agent task 的持久化执行计划。完成一步后应立即标记完成。")
                .stringProperty("action", "操作类型: create, complete, update", false)
                .arrayProperty("tasks", "任务列表（create 时必填）", taskItem, false)
                .intProperty("task_index", "任务序号（complete/update 时使用，从 1 开始）", false)
                .stringProperty("title", "更新后的任务标题（update 时使用）", false)
                .stringProperty("description", "更新后的任务描述（update 时使用）", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String action = args.has("action") ? args.get("action").getAsString() : "create";
        if ("complete".equalsIgnoreCase(action)) {
            return completeTask(context, args);
        }
        if ("update".equalsIgnoreCase(action)) {
            return updatePlan(context, args);
        }
        return createPlan(context, args);
    }

    private ToolResult createPlan(AgentContext context, JsonObject args) {
        if (!args.has("tasks") || !args.get("tasks").isJsonArray()) {
            return ToolResult.failed("tasks 参数必须是数组");
        }
        JsonArray tasksArray = args.getAsJsonArray("tasks");
        if (tasksArray.isEmpty()) {
            return ToolResult.failed("计划至少需要一个任务");
        }
        if (tasksArray.size() > 30) {
            return ToolResult.failed("计划最多 30 个任务");
        }
        List<AgentRunPlanService.PlanDraft> drafts = new ArrayList<>();
        for (int index = 0; index < tasksArray.size(); index++) {
            JsonElement element = tasksArray.get(index);
            if (!element.isJsonObject()) {
                return ToolResult.failed("任务 " + (index + 1) + " 必须是对象");
            }
            JsonObject task = element.getAsJsonObject();
            String title = task.has("title") ? task.get("title").getAsString().strip() : "";
            if (title.isBlank()) {
                return ToolResult.failed("任务 " + (index + 1) + " 的 title 必填");
            }
            String description = task.has("description") ? task.get("description").getAsString() : "";
            drafts.add(new AgentRunPlanService.PlanDraft(title, description, false));
        }
        AgentRunPlanService.Projection projection = planService.replace(
                context.getTaskId(), context.getExecutionEpoch(), drafts, "create_plan");
        projection.applyTo(context);

        StringBuilder summary = new StringBuilder("已创建持久化执行计划:\n");
        for (int index = 0; index < drafts.size(); index++) {
            AgentRunPlanService.PlanDraft draft = drafts.get(index);
            summary.append(index + 1).append(". ").append(draft.title());
            if (!draft.description().isBlank()) summary.append(" - ").append(draft.description());
            summary.append('\n');
        }
        summary.append("\n请按顺序执行。完成后调用 create_plan(action=\"complete\", task_index=N)。");
        return ToolResult.ok(summary.toString());
    }

    private ToolResult completeTask(AgentContext context, JsonObject args) {
        if (!args.has("task_index")) {
            return ToolResult.failed("task_index 参数必填");
        }
        int index = args.get("task_index").getAsInt() - 1;
        AgentRunPlanService.Projection current = planService.load(context.getTaskId());
        current.applyTo(context);
        if (current.items().isEmpty()) {
            return ToolResult.failed("当前没有持久化执行计划，请先创建计划");
        }
        if (index < 0 || index >= current.items().size()) {
            return ToolResult.failed("task_index 超出范围，有效范围: 1-" + current.items().size());
        }
        AgentRunPlanService.PlanItem item = current.items().get(index);
        if (requiresVerification(item) && !context.hasTrustedVerification()) {
            return ToolResult.failed("Verification task cannot be completed before a successful test, build, or manual file verification.");
        }
        AgentRunPlanService.Projection updated = planService.complete(
                context.getTaskId(), context.getExecutionEpoch(), index, "create_plan");
        updated.applyTo(context);

        StringBuilder summary = new StringBuilder();
        summary.append("✅ 任务 ").append(index + 1).append(" 已完成: ").append(item.title()).append("\n\n");
        summary.append(context.getPlanSummary());
        if (updated.currentIndex() >= 0) {
            summary.append("\n下一步请执行任务 ").append(updated.currentIndex() + 1).append(": ")
                    .append(updated.items().get(updated.currentIndex()).title());
        } else {
            summary.append("\n🎉 所有任务已完成，请给出最终总结回复。");
        }
        return ToolResult.ok(summary.toString());
    }

    private ToolResult updatePlan(AgentContext context, JsonObject args) {
        AgentRunPlanService.Projection current = planService.load(context.getTaskId());
        current.applyTo(context);
        if (current.items().isEmpty()) {
            return ToolResult.failed("当前没有持久化执行计划，请先创建计划");
        }
        if (!args.has("task_index") || !args.has("title")) {
            return ToolResult.ok("当前计划:\n" + context.getPlanSummary());
        }
        int index = args.get("task_index").getAsInt() - 1;
        if (index < 0 || index >= current.items().size()) {
            return ToolResult.failed("task_index 超出范围，有效范围: 1-" + current.items().size());
        }
        String title = args.get("title").getAsString().strip();
        if (title.isBlank()) {
            return ToolResult.failed("title 不能为空");
        }
        String description = args.has("description") ? args.get("description").getAsString() : null;
        AgentRunPlanService.Projection updated = planService.update(
                context.getTaskId(), context.getExecutionEpoch(), index, title, description, "create_plan");
        updated.applyTo(context);
        return ToolResult.ok("已更新任务 " + (index + 1) + "\n\n" + context.getPlanSummary());
    }

    private boolean requiresVerification(AgentRunPlanService.PlanItem item) {
        String text = ((item.title() == null ? "" : item.title()) + " "
                + (item.description() == null ? "" : item.description())).toLowerCase(Locale.ROOT);
        return text.contains("verify") || text.contains("verification") || text.contains("test")
                || text.contains("build") || text.contains("compile") || text.contains("lint")
                || text.contains("验证") || text.contains("测试") || text.contains("构建") || text.contains("编译");
    }
}