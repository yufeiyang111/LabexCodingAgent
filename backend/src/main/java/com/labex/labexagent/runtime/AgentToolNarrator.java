package com.labex.labexagent.runtime;

import com.google.gson.JsonObject;
import com.labex.labexagent.tool.ToolResult;
import java.util.List;
import java.util.Locale;

/**
 * 负责把工具动作与结果转换为用户可见的过程说明，不持有任务或 SSE 状态。
 */
@org.springframework.stereotype.Service
public class AgentToolNarrator {
    String buildToolThought(String toolName, JsonObject args, boolean recoveredFromText, String visibleLanguage) {
        String tool = this.safeTool(toolName);
        String target = this.toolTarget(tool, args);
        String readable = target.isBlank() ? tool : target;
        if (this.isChineseLanguage(visibleLanguage)) {
            if (recoveredFromText) {
                return "模型没有使用标准工具格式，但我识别到了工具意图。现在执行：" + readable + "。";
            }
            if (this.isWriteAction(tool)) {
                String[] writeVariants = {
                    "需要修改 " + readable + "，现在写入变更。",
                    readable + " 需要更新，我会直接应用必要改动。",
                    "正在把本轮需要的修改写入 " + readable + "。",
                    "开始更新 " + readable + "，改完后会做验证。"
                };
                return writeVariants[(int)(System.nanoTime() % writeVariants.length)];
            }
            if (this.isVerificationAction(tool)) {
                String[] verifyVariants = {
                    "运行 " + readable + " 验证改动是否生效。",
                    "用 " + readable + " 做一次针对性检查。",
                    "进入验证步骤，执行 " + readable + "。",
                    "通过 " + readable + " 确认结果。"
                };
                return verifyVariants[(int)(System.nanoTime() % verifyVariants.length)];
            }
            return switch (tool) {
                case "list_files", "glob" -> "查看项目结构，定位 " + readable + "。";
                case "read_file", "read" -> "读取 " + readable + "，先确认当前实现。";
                case "search_code", "codesearch", "grep" -> "搜索 " + readable + "，定位相关代码位置。";
                case "create_plan", "plan", "todo_write", "todowrite", "todo" -> "先把任务拆成可验证的步骤，避免反复试错。";
                case "question" -> "当前需要用户补充一个关键决策，先发起提问。";
                default -> "执行 " + readable + "，推进当前步骤。";
            };
        }
        if (recoveredFromText) {
            return "Model didn't use standard tool format, but I detected tool intent from text. Executing: " + readable + ".";
        }
        if (this.isWriteAction(tool)) {
            String[] writeVariants = {
                "Need to modify " + readable + ". Will write the changes now.",
                "File " + readable + " needs updating. Writing changes...",
                "Applying changes to " + readable + ".",
                "Writing to " + readable + " with the required modifications."
            };
            return writeVariants[(int)(System.nanoTime() % writeVariants.length)];
        }
        if (this.isVerificationAction(tool)) {
            String[] verifyVariants = {
                "Running " + readable + " to verify the changes work correctly.",
                "Testing with " + readable + " to confirm the fix.",
                "Verification step: executing " + readable + ".",
                "Validating results by running " + readable + "."
            };
            return verifyVariants[(int)(System.nanoTime() % verifyVariants.length)];
        }
        return switch (tool) {
            case "list_files", "glob" -> "Checking project structure with " + readable + " to locate target files.";
            case "read_file", "read" -> "Reading " + readable + " to understand current content before editing.";
            case "search_code", "codesearch", "grep" -> "Searching for " + readable + " to find relevant code locations.";
            case "create_plan", "plan", "todo_write", "todowrite", "todo" -> "Breaking down the task into actionable steps with a plan.";
            default -> "Executing " + readable + " to proceed with the task.";
        };
    }

    private String taskState(String content) {
        if (content == null || content.isBlank()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<task\\b[^>]*\\bstate=[\\\"'](running|error|completed)[\\\"']", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(content);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    String buildResultThought(String toolName, JsonObject args, ToolResult result, String visibleLanguage) {
        String tool = this.safeTool(toolName);
        String target = this.toolTarget(tool, args);
        String content = result == null ? "" : result.getContent();
        String compact = this.limitForThought(content, 180);
        if ("task".equals(tool) || "subagent".equals(tool)) {
            String state = taskState(content);
            if ("running".equals(state) && result != null && result.isSuccess()) {
                return this.isChineseLanguage(visibleLanguage)
                        ? "子代理已启动并在后台调研，等待它返回持久化结论。"
                        : "The subagent has started and is working in the background; waiting for its durable result.";
            }
            if ("error".equals(state)) {
                String error = compact.isBlank() ? "子代理执行失败" : compact;
                return this.isChineseLanguage(visibleLanguage)
                        ? "子代理执行失败：" + error
                        : "Subagent execution failed: " + error;
            }
        }
        if (this.isCompletedNonZeroShellResult(result)) {
            String exit = String.valueOf(result.getExecutionExitCode());
            return this.isChineseLanguage(visibleLanguage)
                    ? "命令已执行完毕，但退出码为 " + exit + "；需要结合输出判断下一步，不能直接视为验证通过。"
                    : "The command completed with exit code " + exit
                            + "; inspect its output before treating it as a successful verification.";
        }
        if (this.isChineseLanguage(visibleLanguage)) {
            if (result == null || !result.isSuccess()) {
                String err = compact.isBlank() ? "没有输出" : compact;
                String[] failVariants = {
                    "执行失败" + this.withTarget(target) + "：" + err + "。接下来会调整做法。",
                    "在 " + this.withTarget(target) + " 上遇到错误：" + err + "。需要换一种路径处理。",
                    "工具执行没有成功" + this.withTarget(target) + "，关键信息是：" + err
                };
                return failVariants[(int)(System.nanoTime() % failVariants.length)];
            }
            if (result.getPendingChangeId() != null) {
                if (this.isVerificationAction(tool)) {
                    return "\u547d\u4ee4\u5df2\u4fee\u6539\u5de5\u4f5c\u533a\u6587\u4ef6\uff0c\u53d8\u66f4\u5df2\u8bb0\u5f55\uff0c\u4e0b\u4e00\u6b65\u505a\u9a8c\u8bc1\u3002";
                }
                return "文件已修改" + this.withTarget(target) + "，变更已记录，下一步做验证。";
            }
            if (this.isWriteAction(tool)) {
                String[] writeOkVariants = {
                    "写入完成" + this.withTarget(target) + "，现在检查结果。",
                    "已更新" + this.withTarget(target) + "，接下来验证内容。",
                    "修改已落到文件" + this.withTarget(target) + "，继续做针对性校验。"
                };
                return writeOkVariants[(int)(System.nanoTime() % writeOkVariants.length)];
            }
            if (this.isVerificationAction(tool)) {
                String lower = compact.toLowerCase(Locale.ROOT);
                if (lower.contains("success") || lower.contains("passed") || lower.contains("ok") || compact.contains("成功") || compact.contains("通过")) {
                    return "验证通过" + this.withTarget(target) + "。关键输出：" + compact + "。";
                }
                return "验证命令已完成" + this.withTarget(target) + "：" + compact + "。我会据此判断是否还需要修复。";
            }
            return switch (tool) {
                case "list_files", "glob" -> "目录已检查" + this.withTarget(target) + "，项目结构更清楚了。";
                case "read_file", "read" -> "已读取" + this.withTarget(target) + "，可以基于当前内容继续修改。";
                case "search_code", "codesearch", "grep" -> "搜索完成" + this.withTarget(target) + "，找到了需要关注的位置。";
                case "create_plan", "plan", "todo_write", "todowrite", "todo" -> this.planResultSentence(compact, visibleLanguage);
                case "question" -> "已收到用户输入，继续按新的信息执行。";
                default -> "步骤完成" + this.withTarget(target) + "。结果摘要：" + compact + "。";
            };
        }
        if (result == null || !result.isSuccess()) {
            String err = compact.isBlank() ? "No output" : compact;
            String[] failVariants = {
                "Command failed" + this.withTarget(target) + ": " + err + ". Will adjust approach.",
                "Error on " + this.withTarget(target) + ": " + err + ". Trying alternative.",
                "Failed to execute " + this.withTarget(target) + ". Analyzing: " + err
            };
            return failVariants[(int)(System.nanoTime() % failVariants.length)];
        }
        if (result.getPendingChangeId() != null) {
            if (this.isVerificationAction(tool)) {
                return "Command modified workspace files. Changes applied. Will verify next.";
            }
            return "File modified" + this.withTarget(target) + ". Changes applied. Will verify next.";
        }
        if (this.isWriteAction(tool)) {
            String[] writeOkVariants = {
                "Written to " + this.withTarget(target) + ". Checking result...",
                "Write complete for " + this.withTarget(target) + ". Verifying content.",
                "Updated " + this.withTarget(target) + ". Will validate the changes."
            };
            return writeOkVariants[(int)(System.nanoTime() % writeOkVariants.length)];
        }
        if (this.isVerificationAction(tool)) {
            String lower = compact.toLowerCase();
            if (lower.contains("success") || lower.contains("passed") || lower.contains("ok")) {
                return "Verification passed" + this.withTarget(target) + ". Key output: " + compact + ". Moving to next step.";
            }
            return "Verification done" + this.withTarget(target) + ": " + compact + ". Will analyze and proceed.";
        }
        return switch (tool) {
            case "list_files", "glob" -> "Checked directory" + this.withTarget(target) + ". Project structure understood.";
            case "read_file", "read" -> "Read " + this.withTarget(target) + ". Content reviewed, ready to edit.";
            case "search_code", "codesearch", "grep" -> "Search done for " + this.withTarget(target) + ". Found relevant code, will read and modify.";
            case "create_plan", "plan", "todo_write", "todowrite", "todo" -> this.planResultSentence(compact, visibleLanguage);
            default -> "Done" + this.withTarget(target) + ". Result: " + compact + ".";
        };
    }

    String visibleActionSummary(String toolName, JsonObject args) {
        return this.visibleActionSummary(toolName, args, "en");
    }

    String visibleActionSummary(String toolName, JsonObject args, String visibleLanguage) {
        String tool = this.safeTool(toolName);
        String target = this.toolTarget(tool, args);
        if (this.isChineseLanguage(visibleLanguage)) {
            if (this.isWriteAction(tool)) {
                return "编辑 " + this.shortTarget(target, "文件");
            }
            if (this.isVerificationAction(tool)) {
                return "运行 " + this.shortTarget(target, "命令");
            }
            return switch (tool) {
                case "list_files", "glob" -> "查看 " + this.shortTarget(target, "目录");
                case "read_file", "read" -> "读取 " + this.shortTarget(target, "文件");
                case "search_code", "codesearch", "grep" -> "搜索 " + this.shortTarget(target, "代码");
                case "retrieve_context" -> "获取上下文";
                case "create_plan", "plan", "todo_write", "todowrite", "todo" -> "创建计划";
                case "question" -> "询问用户";
                default -> "执行 " + (tool.isBlank() ? "下一步" : tool);
            };
        }
        if (this.isWriteAction(tool)) {
            return "Editing " + this.shortTarget(target, "file");
        }
        if (this.isVerificationAction(tool)) {
            return "Running " + this.shortTarget(target, "command");
        }
        return switch (tool) {
            case "list_files", "glob" -> "List " + this.shortTarget(target, "directory");
            case "read_file", "read" -> "Read " + this.shortTarget(target, "file");
            case "search_code", "codesearch", "grep" -> "Search " + this.shortTarget(target, "code");
            case "retrieve_context" -> "Get context";
            case "create_plan", "plan", "todo_write", "todowrite", "todo" -> "Create plan";
            default -> "Execute " + (tool.isBlank() ? "next step" : tool);
        };
    }

    String visibleActionDetail(String toolName, JsonObject args) {
        return this.visibleActionDetail(toolName, args, "en");
    }

    String visibleActionDetail(String toolName, JsonObject args, String visibleLanguage) {
        String t;
        String tool = this.safeTool(toolName);
        String target = this.toolTarget(tool, args);
        String string = t = target.isBlank() ? tool : target;
        if (this.isChineseLanguage(visibleLanguage)) {
            if (this.isWriteAction(tool)) {
                return "编辑 " + t;
            }
            if (this.isVerificationAction(tool)) {
                return "运行 " + t;
            }
            return switch (tool) {
                case "list_files", "glob" -> "查看 " + t;
                case "read_file", "read" -> "读取 " + t;
                case "search_code", "codesearch", "grep" -> "搜索 " + t;
                case "retrieve_context" -> "获取上下文";
                case "create_plan", "plan", "todo_write", "todowrite", "todo" -> this.planActionSentence(args, visibleLanguage);
                case "question" -> "等待用户回答";
                default -> "执行 " + t;
            };
        }
        if (this.isWriteAction(tool)) {
            return "Edit " + t;
        }
        if (this.isVerificationAction(tool)) {
            return "Run " + t;
        }
        return switch (tool) {
            case "list_files", "glob" -> "List " + t;
            case "read_file", "read" -> "Read " + t;
            case "search_code", "codesearch", "grep" -> "Search " + t;
            case "retrieve_context" -> "Get context";
            case "create_plan", "plan", "todo_write", "todowrite", "todo" -> this.planActionSentence(args, visibleLanguage);
            default -> "Execute " + t;
        };
    }

    String toolTarget(String toolName, JsonObject args) {
        if (args == null) {
            return "";
        }
        for (String key : List.of("path", "file_path", "relativePath", "target_file", "directory", "dir", "cwd", "pattern", "query", "command", "cmd")) {
            String value;
            if (!args.has(key) || args.get(key).isJsonNull() || (value = args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : args.get(key).toString()) == null || value.isBlank()) continue;
            return value.trim();
        }
        if (("create_plan".equals(toolName) || "plan".equals(toolName)) && args.has("action")) {
            return args.get("action").getAsString();
        }
        return "";
    }

    private String planActionSentence(JsonObject args, String visibleLanguage) {
        String action;
        String string = action = args != null && args.has("action") ? args.get("action").getAsString() : "create";
        if (this.isChineseLanguage(visibleLanguage)) {
            if ("complete".equalsIgnoreCase(action)) {
                String index = args != null && args.has("task_index") ? args.get("task_index").getAsString() : "";
                return "把计划中的" + (index.isBlank() ? "当前步骤" : "第 " + index + " 步") + "标记为完成，工具结果已经能证明这一步结束。";
            }
            if ("update".equalsIgnoreCase(action)) {
                return "根据最新发现更新计划，调整后续步骤的顺序或内容。";
            }
            int count = args != null && args.has("tasks") && args.get("tasks").isJsonArray() ? args.getAsJsonArray("tasks").size() : 0;
            return count > 0 ? "把任务拆成 " + count + " 个可执行步骤，每一步都有明确验证方式。" : "先创建行动计划，再开始修改文件。";
        }
        if ("complete".equalsIgnoreCase(action)) {
            String index = args != null && args.has("task_index") ? args.get("task_index").getAsString() : "";
            return "Marking task " + (index.isBlank() ? "current" : index) + " as complete in the plan. Tool results confirm this step is done.";
        }
        if ("update".equalsIgnoreCase(action)) {
            return "Updating the task plan because new findings changed the order or content of subsequent steps.";
        }
        int count = args != null && args.has("tasks") && args.get("tasks").isJsonArray() ? args.getAsJsonArray("tasks").size() : 0;
        return count > 0 ? "Breaking this work into " + count + " executable tasks as a plan. Each task will have a clear verification." : "Creating an action plan before editing files.";
    }

    private String planResultSentence(String compact, String visibleLanguage) {
        if (this.isChineseLanguage(visibleLanguage)) {
            if (compact.contains("all tasks complete") || compact.contains("全部完成")) {
                return "计划中的任务都已完成，可以输出带验证结果的最终总结。";
            }
            if (compact.contains("next step") || compact.contains("下一步")) {
                return "计划已更新，下一步已经明确，会继续执行。";
            }
            if (compact.contains("plan created") || compact.contains("计划")) {
                return "计划已创建，会按步骤执行并标记进度。";
            }
            return "计划状态已更新，继续进入下一步。摘要：" + compact;
        }
        if (compact.contains("all tasks complete")) {
            return "All plan tasks are complete. Ready to output final summary with verification results.";
        }
        if (compact.contains("next step")) {
            return "Plan updated. Next step identified, will continue execution.";
        }
        if (compact.contains("plan created")) {
            return "Plan created with task list. Will execute each step and mark progress.";
        }
        return "Plan state updated. Continuing with next task. Summary: " + compact;
    }

    private String verificationResultSentence(String target, String compact) {
        String lower = compact.toLowerCase(Locale.ROOT);
        if (lower.contains("build success") || lower.contains("success") || lower.contains("passed") || lower.contains("ok")) {
            return "Verification passed" + this.withTarget(target) + ". Changes passed this check. Key output: " + compact + ". Will assess if more checks needed or ready for final summary.";
        }
        if (lower.contains("error") || lower.contains("failed") || lower.contains("exception")) {
            return "Verification exposed issue" + this.withTarget(target) + ". Key output: " + compact + ". Will trace this error for recovery.";
        }
        return "Verification command returned" + this.withTarget(target) + ", but no clear success/failure marker in output. Summary: " + compact;
    }

    private String readableTarget(String target, String fallback) {
        return target == null || target.isBlank() ? fallback : " `" + this.limitForThought(target, 120) + "`";
    }

    private String withTarget(String target) {
        return target == null || target.isBlank() ? "" : " (`" + this.limitForThought(target, 120) + "`)";
    }

    private String shortTarget(String target, String fallback) {
        if (target == null || target.isBlank()) {
            return fallback;
        }
        String compact = target.trim().replaceAll("\\s+", " ");
        return compact.length() <= 28 ? compact : compact.substring(0, 28) + "...";
    }

    private boolean isWriteAction(String toolName) {
        String safe = this.safeTool(toolName);
        return "write_file".equals(safe) || "write".equals(safe) || "edit_file".equals(safe) || "edit".equals(safe) || "apply_patch".equals(safe) || "patch".equals(safe);
    }

    private boolean isVerificationAction(String toolName) {
        String safe = this.safeTool(toolName);
        return "run_tests".equals(safe) || "execute_code".equals(safe) || "shell".equals(safe) || "bash".equals(safe) || "run_command".equals(safe);
    }

    private String safeTool(String toolName) {
        return toolName == null ? "" : toolName.trim();
    }

    private String limitForThought(String text, int max) {
        if (text == null) return "";
        String compact = text.trim().replaceAll("\\s+", " ");
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    private boolean isCompletedNonZeroShellResult(ToolResult result) {
        return result != null && result.isSuccess()
                && "failed".equals(result.getExecutionStatus())
                && result.getExecutionExitCode() != null && result.getExecutionExitCode() != 0
                && result.getContent() != null && result.getContent().contains("outcome=non_zero_exit");
    }
    private boolean isChineseLanguage(String visibleLanguage) {
        return "zh".equalsIgnoreCase(visibleLanguage);
    }
}
