package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunModeService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 计划模式退出工具（参考 OpenCode PlanExitTool）
 * 在 plan 模式下完成计划制定后，调用此工具切换到 build 模式开始执行。
 * 模式切换必须先经 {@link AgentRunModeService} 持久化（CAS + RUN_MODE_CHANGED），
 * 内存 context 只在 durable 切换成功后由该服务更新；下一轮 Provider 调用会重建
 * mode policy、工具 schema、系统提示词与 prompt-cache key。
 */
@Component
public class PlanExitTool implements AgentTool {
    private final AgentRunModeService modeService;

    public PlanExitTool(AgentRunModeService modeService) {
        this.modeService = modeService;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
            .name("plan_exit")
            .description("Exit plan mode and switch to build mode for execution. " +
                "Call this after your plan is complete and ready to implement.")
            .stringProperty("plan_summary", "brief summary of the plan for the build agent", true)
            .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        if (context == null || context.getExecutionFence() == null) {
            throw new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                    AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.INVALID_FENCE);
        }
        String planSummary = args.has("plan_summary") ? args.get("plan_summary").getAsString() : "";

        if (planSummary.isBlank()) {
            return ToolResult.failed("plan_summary is required. Describe your plan before exiting plan mode.");
        }

        this.modeService.transitionPlanToBuild(
                context.getTaskId(),
                context.getExecutionFence(),
                AgentRunModeService.planToBuildKey(context.getTaskId()),
                context);

        return ToolResult.ok(
            "Switched from plan mode to build mode.\n" +
            "Plan summary:\n" + planSummary + "\n\n" +
            "You can now use all tools including edit, write, shell, etc. to implement the plan."
        );
    }
}
