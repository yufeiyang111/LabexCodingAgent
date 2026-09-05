package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.entity.AgentSubagent;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentSubagentProperties;
import com.labex.labexagent.run.AgentSubagentService;
import com.labex.labexagent.run.SubagentLaunchService;
import com.labex.labexagent.run.SubagentState;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentToolTurnExecutor;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * task 工具：派发独立子代理会话（独立 conversation/task，经 AgentLoopEngine 主运行时）。
 * 前台调用阻塞等待子任务终态（对齐 OpenCode 默认行为）；background 立即返回 running。
 */
@Component
public class TaskTool
implements AgentTool {
    private final SubagentLaunchService launchService;
    private final AgentSubagentService subagentService;
    private final AgentCancellationRegistry cancellationRegistry;
    private final AgentSubagentProperties properties;

    @Autowired
    public TaskTool(SubagentLaunchService launchService,
                    AgentSubagentService subagentService,
                    AgentCancellationRegistry cancellationRegistry,
                    AgentSubagentProperties properties) {
        this.launchService = launchService;
        this.subagentService = subagentService;
        this.cancellationRegistry = cancellationRegistry;
        this.properties = properties == null ? new AgentSubagentProperties() : properties;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("task")
                .description("""
                        Launch a new agent to handle complex, multistep tasks autonomously.

                        When using the Task tool, you must specify a subagent_type parameter to select which agent type to use.

                        When NOT to use the Task tool:
                        - If you want to read a specific file path, use the Read tool instead of the Task tool, to find the match more quickly
                        - If you are searching for a specific class definition like "class Foo", use the Grep tool instead
                        - If you are searching for code within a specific file or set of 2-3 files, use the Read tool instead
                        - If no available agent type is a good fit, use other tools directly

                        Usage notes:
                        1. Launch multiple agents concurrently whenever possible to maximize performance; do that in a single message with multiple tool uses.
                        2. Once you have delegated work to an agent, do not duplicate that work yourself. Continue with non-overlapping work, or wait for the result.
                        3. The agent returns a single message back to you; it is not shown to the user. Summarize the result for the user yourself.
                        4. Each invocation starts a fresh context unless you pass task_id of a prior subagent to continue the same session (keeping its previous messages and tool outputs). Fresh prompts must be highly detailed and self-contained, and must state exactly what the final report should contain.
                        5. Clearly tell the agent whether you expect it to write code or only research (search/read/webfetch), since it cannot see the user's intent; include how to verify its work when relevant.
                        6. The agent's outputs should generally be trusted.""")
                .stringProperty("description", "A short (3-5 words) description of the task", true)
                .stringProperty("prompt", "The actionable task instructions for the subagent to perform", false)
                .stringProperty("subagent_type", "type of specialized agent to use: 'explore', 'scout', or 'general' (default: 'general')", false)
                .stringProperty("name", "custom descriptive role name for the subagent, e.g. 'Frontend Architecture Explorer' or 'API Protocol Scout'", false)
                .stringProperty("task_id", "optional prior task_id to resume and continue a previous subagent session", false)
                .booleanProperty("background", "run asynchronously without blocking the parent agent (default: false)", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String name = ToolSupport.stringArgMulti(args, "", "name", "agent_name", "subagent_name", "role").trim();
        String description = ToolSupport.stringArg(args, "description", "").trim();
        String prompt = ToolSupport.stringArgMulti(args, "", "prompt", "task", "question").trim();
        String priorTaskId = ToolSupport.stringArgMulti(args, "", "task_id", "taskId", "session_id", "sessionId").trim();
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
        boolean background = args.has("background") && !args.get("background").isJsonNull()
                && args.get("background").getAsBoolean();
        // task_id 续聊：把先前会话的结论作为上下文注入新派发（真正的多轮延续仍通过子代理标签页完成）。
        String priorContext = resumeContext(priorTaskId);
        if (!priorContext.isBlank()) {
            prompt = prompt + "\n\n" + priorContext.trim();
        }

        try {
            var dispatch = this.launchService.launch(new SubagentLaunchService.LaunchSpec(
                    context.getSessionId(), context.getStudentId(), context.getProject(),
                    context.getConversationId(), context.getTaskId(),
                    name, description, prompt, subagentType, null, background,
                    AgentToolTurnExecutor.currentToolCallId()));

            if (background) {
                return ToolResult.ok(formatSubtaskOutput(String.valueOf(dispatch.subagent().getSubagentId()),
                        name, subagentType, description,
                        "The task is working in the background. You will be notified automatically when it finishes."));
            }

            long timeoutMs = Math.max(1_000L, this.properties.getTaskTimeoutMs());
            try {
                dispatch.completion().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                // 超时不终止子任务本身：它转为后台继续运行并稍后回传摘要；父任务先解除阻塞。
                try {
                    this.cancellationRegistry.cancel(dispatch.conversation() == null ? "" :
                            context.getSessionId() + ":subagent:" + dispatch.subagent().getSubagentId());
                } catch (RuntimeException ignored) {
                    // 子任务尚未注册取消令牌时忽略；持久化恢复路径仍可接管。
                }
                return ToolResult.failed(formatSubtaskError(String.valueOf(dispatch.subagent().getSubagentId()),
                        name, subagentType, description, "timeout",
                        "Subagent exceeded " + (timeoutMs / 1_000) + "s foreground budget; it keeps running in the background."));
            }

            // future 完成后行收束与父级解除等待在同一事务链内，但行状态写入后仍可能
            // 有读-写竞态窗口：这里做有界轮询，等待行进入终态而不是一次性误判。
            AgentSubagent finished = this.awaitTerminalRow(dispatch.subagent().getSubagentId());
            String state = finished == null ? dispatch.subagent().getStatus() : finished.getStatus();
            if (!SubagentState.COMPLETED.persisted().equalsIgnoreCase(state == null ? "" : state)) {
                if (finished != null && isRecoverableState(state)) {
                    // 子代理仍在后台运行（如等待审批/用户输入后被恢复路径接管），
                    // 不是失败：父任务解除阻塞，摘要稍后通过 SUBAGENT_SUMMARY 回传。
                    return ToolResult.failed(formatSubtaskError(
                            String.valueOf(dispatch.subagent().getSubagentId()), name, subagentType, description,
                            state, "Subagent is still running (state " + state
                            + "); it continues in the background and its summary is delivered when it finishes."));
                }
                String failure = finished == null ? null : finished.getSummary();
                if (failure == null || failure.isBlank()) failure = "Subagent ended in state " + state;
                return ToolResult.failed(formatSubtaskError(
                        String.valueOf(dispatch.subagent().getSubagentId()), name, subagentType, description, state, failure));
            }
            String content = finished == null ? "" : finished.getSummary();
            if (content == null || content.isBlank()) return ToolResult.failed("Subtask returned no content.");
            return ToolResult.ok(formatSubtaskOutput(String.valueOf(finished.getSubagentId()),
                    name, subagentType, description, content));
        } catch (Exception e) {
            String detail = e.getMessage();
            return ToolResult.failed("code=SUBTASK_FAILED\n"
                    + "error_type=" + e.getClass().getSimpleName() + "\n"
                    + "message=" + (detail == null || detail.isBlank()
                    ? "subtask dispatch failed"
                    : ToolSupport.limit(detail, 512)));
        }
    }

    /** 有界轮询子代理行直至进入终态；等待上限 3s，用于吸收行收束与 future 完成间的写读竞态。 */
    private AgentSubagent awaitTerminalRow(long subagentId) {
        long deadline = System.currentTimeMillis() + 3_000L;
        AgentSubagent row = null;
        while (System.currentTimeMillis() < deadline) {
            row = this.subagentService.findById(subagentId);
            if (row != null && isTerminalState(row.getStatus())) {
                return row;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return row;
            }
        }
        return row;
    }

    private static boolean isTerminalState(String state) {
        if (state == null) return false;
        String normalized = state.toLowerCase(Locale.ROOT);
        return "completed".equals(normalized) || "failed".equals(normalized) || "cancelled".equals(normalized);
    }

    private static boolean isRecoverableState(String state) {
        if (state == null) return false;
        String normalized = state.toLowerCase(Locale.ROOT);
        return "running".equals(normalized) || "queued".equals(normalized)
                || normalized.startsWith("waiting_") || "retry_backoff".equals(normalized);
    }

    private String resumeContext(String priorTaskId) {
        if (priorTaskId == null || priorTaskId.isBlank() || this.subagentService == null) {
            return "";
        }
        try {
            AgentSubagent priorSubagent = this.subagentService.findById(Long.parseLong(priorTaskId));
            if (priorSubagent == null) {
                return "";
            }
            int limit = this.properties.getPriorContextMaxChars();
            return "[Previous Subagent Session #" + priorTaskId + " Context]\n"
                    + "Prior Prompt: " + ToolSupport.limit(priorSubagent.getInstructions(), limit)
                    + "\nPrior Summary: " + ToolSupport.limit(priorSubagent.getSummary(), limit);
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private String normalizeSubagentType(String value) {
        String normalized = value == null ? "general" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("explore") || normalized.equals("scout")) {
            return normalized;
        }
        return "general";
    }

    private String formatSubtaskOutput(String taskId, String name, String subagentType, String description, String content) {
        return "<task id=\"" + taskId + "\" name=\"" + escapeXml(name) + "\" state=\"completed\" agent=\"" + subagentType + "\">\n"
                + "<name>" + escapeXml(name) + "</name>\n"
                + "<summary>" + escapeXml(description) + "</summary>\n"
                + "<task_result>\n"
                + ToolSupport.limit(content, this.properties.getToolResultMaxChars())
                + "\n</task_result>\n"
                + "</task>";
    }

    private String formatSubtaskError(String taskId, String name, String subagentType,
                                      String description, String state, String content) {
        return "<task id=\"" + escapeXml(taskId) + "\" name=\"" + escapeXml(name)
                + "\" state=\"error\" agent=\"" + escapeXml(subagentType) + "\">\n"
                + "<name>" + escapeXml(name) + "</name>\n"
                + "<summary>" + escapeXml(description) + "</summary>\n"
                + "<state>" + escapeXml(state) + "</state>\n"
                + "<task_error>" + escapeXml(ToolSupport.limit(content, this.properties.getToolResultMaxChars()))
                + "</task_error>\n"
                + "</task>";
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
