package com.labex.labexagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.run.AgentRunProgressProjectionService;
import com.labex.labexagent.run.PlanVerificationEvidenceService;
import com.labex.labexagent.run.ExecutionFence;
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
    private static final int MIN_TITLE_LENGTH = 4;
    private static final List<String> VAGUE_PHRASES = List.of(
            "improve the code", "improve code", "optimize the code", "optimize code",
            "make it better", "make better", "fix everything", "test everything",
            "do everything", "expand features", "continue expanding", "improve everything",
            "优化代码", "优化一下", "改进代码", "改进一下", "让代码更好", "继续扩展", "继续加功能",
            "全部测试", "修好一切");

    private final AgentRunPlanService planService;
    private final AgentRunProgressProjectionService progressProjectionService;
    private final PlanVerificationEvidenceService verificationEvidenceService;

    @org.springframework.beans.factory.annotation.Autowired
    public CreatePlanTool(AgentRunPlanService planService,
                          AgentRunProgressProjectionService progressProjectionService,
                          PlanVerificationEvidenceService verificationEvidenceService) {
        this.planService = planService;
        this.progressProjectionService = progressProjectionService;
        this.verificationEvidenceService = verificationEvidenceService;
    }

    public CreatePlanTool(AgentRunPlanService planService,
                          AgentRunProgressProjectionService progressProjectionService) {
        this(planService, progressProjectionService, null);
    }

    public CreatePlanTool(AgentRunPlanService planService) {
        this(planService, null, null);
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> taskItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string", "description", "Task title"),
                        "description", Map.of("type", "string", "description", "Task description")),
                "required", List.of("title"));
        return ToolDefinition.builder()
                .name("create_plan")
                .description("Create or manage the durable execution plan for the current agent task. Mark each step complete promptly after its work is finished.")
                .stringProperty("action", "Operation: create, complete, or update", false)
                .arrayProperty("tasks", "Task list (required for create)", taskItem, false)
                .intProperty("task_index", "Task index (used by complete or update; starts at 1)", false)
                .stringProperty("title", "Updated task title (used by update)", false)
                .stringProperty("description", "Updated task description (used by update)", false)
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
        ExecutionFence fence = requireActiveFence(context);
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
            String qualityError = validatePlanItem(index, title);
            if (qualityError != null) {
                return ToolResult.failed(qualityError);
            }
            String description = task.has("description") ? task.get("description").getAsString() : "";
            drafts.add(new AgentRunPlanService.PlanDraft(title, description, false));
        }
        AgentRunPlanService.Projection projection = planService.replace(
                fence, context.getTaskId(), context.getExecutionEpoch(), drafts, "create_plan");
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
        ExecutionFence fence = requireActiveFence(context);
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
        refreshDurableProgress(context);
        if (verificationEvidenceService != null) {
            PlanVerificationEvidenceService.Assessment assessment = verificationEvidenceService.assess(
                    context.getTaskId(), context.getExecutionEpoch(), item);
            if (assessment.required() && !assessment.satisfied()) {
                return ToolResult.failed("verification_required\nreason=" + assessment.reasonCode()
                        + "\nrequirement_kind=" + assessment.requirementKind()
                        + "\nrequired_target=" + assessment.requiredTarget());
            }
        } else if (requiresVerification(item) && !context.hasTrustedVerification()) {
            return ToolResult.failed("Verification task cannot be completed before a successful test, build, or manual file verification.");
        }
        AgentRunPlanService.Projection updated = planService.complete(
                fence, context.getTaskId(), context.getExecutionEpoch(), index, "create_plan");
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
        ExecutionFence fence = requireActiveFence(context);
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
        String qualityError = validatePlanItem(index, title);
        if (qualityError != null) {
            return ToolResult.failed(qualityError);
        }
        String description = args.has("description") ? args.get("description").getAsString() : null;
        AgentRunPlanService.Projection updated = planService.update(
                fence, context.getTaskId(), context.getExecutionEpoch(), index, title, description, "create_plan");
        updated.applyTo(context);
        return ToolResult.ok("已更新任务 " + (index + 1) + "\n\n" + context.getPlanSummary());
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

    private void refreshDurableProgress(AgentContext context) {
        if (progressProjectionService == null || context == null || context.getTaskId() == null) {
            return;
        }
        AgentRunProgressProjectionService.Projection projection = progressProjectionService.load(
                context.getTaskId(), context.getExecutionEpoch());
        if (projection != null) {
            projection.applyTo(context);
        }
    }

    private boolean requiresVerification(AgentRunPlanService.PlanItem item) {
        String text = ((item.title() == null ? "" : item.title()) + " "
                + (item.description() == null ? "" : item.description())).toLowerCase(Locale.ROOT);
        return text.contains("verify") || text.contains("verification") || text.contains("test")
                || text.contains("build") || text.contains("compile") || text.contains("lint")
                || text.contains("验证") || text.contains("测试") || text.contains("构建") || text.contains("编译");
    }

    /** 计划项必须具体、可验证，拒绝含糊标题（含糊计划是死循环的主要来源）。 */
    static String validatePlanItem(int index, String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.length() < MIN_TITLE_LENGTH) {
            return "任务 " + (index + 1) + " 标题过于笼统（过短），请改为具体、可验证的表述。示例: \"修复 auth.py 第 45-60 行的登录校验逻辑\"";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String phrase : VAGUE_PHRASES) {
            if (lower.contains(phrase)) {
                return "任务 " + (index + 1) + " 标题包含模糊描述（" + phrase + "），无法验证完成状态。请改为具体、可验证的表述。示例: \"修复 auth.py 第 45-60 行的登录校验逻辑\"";
            }
        }
        return null;
    }
}