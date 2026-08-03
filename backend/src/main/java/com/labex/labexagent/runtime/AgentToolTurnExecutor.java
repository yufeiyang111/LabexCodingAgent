package com.labex.labexagent.runtime;

import com.google.gson.JsonObject;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.labexagent.tool.ToolResult;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 工具本轮的 Schema/模式门禁与执行 watchdog。交互暂停和任务状态仍由上层编排。 */
@org.springframework.stereotype.Service
public final class AgentToolTurnExecutor {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            0, 32, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            runnable -> { Thread thread = new Thread(runnable, "labex-agent-tool-turn"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private final ToolRegistry registry;
    private final ToolArgumentSchemaValidator argumentSchemaValidator = new ToolArgumentSchemaValidator();

    public AgentToolTurnExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolResolution resolve(AgentContext context, String toolName, String language) {
        if (context == null || !context.isToolSelected(toolName)) {
            return ToolResolution.rejected(ToolResult.failed(local(language,
                    "工具 `" + toolName + "` 未在本轮模型请求中暴露，已拒绝执行。",
                    "Tool '" + toolName + "' was not exposed in this model turn.")));
        }
        if (!registry.isToolAllowed(context.getMode(), toolName)) {
            return ToolResolution.rejected(ToolResult.failed(local(language,
                    "工具 `" + toolName + "` 在 " + context.getMode() + " 模式下不可用。",
                    "Tool '" + toolName + "' is not allowed in " + context.getMode() + " mode.")));
        }
        AgentTool tool = registry.get(toolName);
        return tool == null
                ? ToolResolution.rejected(ToolResult.failed(local(language, "未知工具：" + toolName, "Unknown tool: " + toolName)))
                : ToolResolution.allowed(tool);
    }

    /** 原生 structured tool call 必须先解析 arguments，再通过与文本恢复相同的本轮门禁。 */
    public ToolInputResolution resolveNative(AgentContext context,
                                             AgentModelTurnExecutor.NativeToolCall call,
                                             String language) {
        String toolName = call == null ? "" : call.toolName();
        ToolCallArgumentsParser.ParseResult parsed = ToolCallArgumentsParser.parse(
                call == null ? null : call.toolArguments());
        if (!parsed.valid()) {
            String detail = local(language,
                    "工具 `" + toolName + "` 的原生 arguments 不是完整 JSON object（"
                            + parsed.reasonCode() + "），已拒绝执行。",
                    "Native arguments for tool '" + toolName + "' are not a complete JSON object ("
                            + parsed.reasonCode() + ") and were rejected.");
            return ToolInputResolution.rejected(parsed.arguments(), ToolResult.failed(detail), parsed.reasonCode());
        }
        return resolveInput(context, toolName, parsed.arguments(), language);
    }

    /** 文本兼容恢复保留既有调用接口，但复用统一参数门禁。 */
    public ToolResolution resolveRecovered(AgentContext context, String toolName, JsonObject arguments, String language) {
        return resolveInput(context, toolName, arguments, language).resolution();
    }

    private ToolInputResolution resolveInput(AgentContext context, String toolName,
                                             JsonObject arguments, String language) {
        JsonObject normalizedArguments = arguments == null ? new JsonObject() : arguments.deepCopy();
        ToolResolution resolution = resolve(context, toolName, language);
        if (!resolution.allowed()) {
            return ToolInputResolution.rejected(normalizedArguments, resolution.rejection(), "tool_not_available");
        }
        ToolArgumentSchemaValidator.Validation validation = argumentSchemaValidator.validate(
                resolution.tool().definition(), normalizedArguments);
        if (validation.valid()) {
            return ToolInputResolution.allowed(normalizedArguments, resolution.tool());
        }
        String detail = local(language,
                "工具 `" + toolName + "` 的参数不符合本轮 schema（" + validation.code()
                        + "，位置 " + validation.path() + "），已拒绝执行。",
                "Arguments for tool '" + toolName + "' do not match this turn's schema ("
                        + validation.code() + " at " + validation.path() + ").");
        return ToolInputResolution.rejected(normalizedArguments, ToolResult.failed(detail), validation.code());
    }

    public ToolResult execute(AgentTool tool, AgentContext context, JsonObject arguments, String toolName) throws Exception {
        long budgetMs = ToolExecutionBudget.timeoutMs(toolName, arguments);
        Future<ToolResult> future = EXECUTOR.submit(() -> tool.execute(context, arguments));
        try {
            return future.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new ToolTimedOutException(toolName, budgetMs);
        } catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt(); throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(cause);
        }
    }

    private String local(String language, String zh, String en) {
        return "en".equalsIgnoreCase(language) ? en : zh;
    }

    public record ToolResolution(AgentTool tool, ToolResult rejection) {
        public static ToolResolution allowed(AgentTool tool) { return new ToolResolution(tool, null); }
        public static ToolResolution rejected(ToolResult result) { return new ToolResolution(null, result); }
        public boolean allowed() { return tool != null; }
    }

    public record ToolInputResolution(JsonObject arguments, ToolResolution resolution, String reasonCode) {
        public ToolInputResolution {
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
            reasonCode = reasonCode == null || reasonCode.isBlank() ? "unknown" : reasonCode;
        }

        public static ToolInputResolution allowed(JsonObject arguments, AgentTool tool) {
            return new ToolInputResolution(arguments, ToolResolution.allowed(tool), "ok");
        }

        public static ToolInputResolution rejected(JsonObject arguments, ToolResult rejection, String reasonCode) {
            return new ToolInputResolution(arguments, ToolResolution.rejected(rejection), reasonCode);
        }

        public boolean allowed() {
            return resolution != null && resolution.allowed();
        }

        public AgentTool tool() {
            return resolution == null ? null : resolution.tool();
        }

        public ToolResult rejection() {
            return resolution == null ? ToolResult.failed("Tool input was rejected.") : resolution.rejection();
        }
    }

    public static final class ToolTimedOutException extends Exception {
        private final long budgetMs;
        public ToolTimedOutException(String toolName, long budgetMs) {
            super("Tool '" + toolName + "' exceeded its execution budget of " + budgetMs + " ms");
            this.budgetMs = budgetMs;
        }
        public long budgetMs() { return budgetMs; }
    }
}
