package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 计划模式退出工具（参考 OpenCode PlanExitTool）
 * 在 plan 模式下完成计划制定后，调用此工具切换到 build 模式开始执行
 */
@Component
public class PlanExitTool implements AgentTool {

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
        String planSummary = args.has("plan_summary") ? args.get("plan_summary").getAsString() : "";

        if (planSummary.isBlank()) {
            return ToolResult.failed("plan_summary is required. Describe your plan before exiting plan mode.");
        }

        // 切换模式
        context.setMode("build");

        return ToolResult.ok(
            "Switched from plan mode to build mode.\n" +
            "Plan summary:\n" + planSummary + "\n\n" +
            "You can now use all tools including edit, write, shell, etc. to implement the plan."
        );
    }
}
