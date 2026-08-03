package com.labex.labexagent.command;

import java.util.Arrays;
import java.util.List;

/**
 * Slash command 元数据。命令必须显式声明由 Agent prompt、客户端动作还是不可用路径处理。
 */
public record CommandInfo(
    String name,                    // 命令名称
    String description,             // 命令描述
    String template,                // LLM 提示模板，仅 AGENT_PROMPT 使用
    String agent,                   // 指定的 Agent
    String model,                   // 覆盖的模型
    boolean subtask,                // 是否作为子任务运行
    CommandSource source,           // 命令来源
    List<String> hints,             // 参数提示（$1, $2, $ARGUMENTS 等）
    List<String> aliases,           // 命令别名
    CommandDispatch dispatch,       // 权威分发类型
    ClientAction clientAction,      // 客户端动作，仅 CLIENT_ACTION 使用
    String unavailableReason        // 不可用原因，仅 UNAVAILABLE 使用
) {
    /**
     * 命令来源枚举。
     */
    public enum CommandSource {
        BUILTIN,
        CONFIG,
        MARKDOWN,
        MCP,
        SKILL
    }

    /**
     * 命令分发类型。禁止通过模板内容猜测命令所有者。
     */
    public enum CommandDispatch {
        AGENT_PROMPT,
        CLIENT_ACTION,
        UNAVAILABLE
    }

    /**
     * 前端可执行动作的稳定协议；不接受任意脚本或方法名。
     */
    public enum ClientAction {
        SESSION_LIST,
        SESSION_NEW,
        CONVERSATION_COMPACT,
        CONVERSATION_FORK,
        CONVERSATION_COPY,
        CONVERSATION_EXPORT,
        TOGGLE_TIMESTAMPS,
        TOGGLE_THINKING,
        MODEL_SETTINGS,
        THEME_SETTINGS,
        CHANGES_PANEL,
        SKILLS_PANEL,
        MCP_PANEL,
        USAGE_PANEL,
        CONTEXT_USAGE,
        PROJECT_STATUS,
        COMMAND_HELP,
        WORKSPACE_EXIT
    }

    public CommandInfo {
        hints = hints == null ? List.of() : List.copyOf(hints);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (dispatch == null) {
            throw new IllegalArgumentException("Slash command 必须显式声明 dispatch");
        }
        switch (dispatch) {
            case AGENT_PROMPT -> {
                if (!hasText(template)) {
                    throw new IllegalArgumentException("AGENT_PROMPT 必须提供非空模板");
                }
                if (clientAction != null || hasText(unavailableReason)) {
                    throw new IllegalArgumentException("AGENT_PROMPT 不能携带客户端动作或不可用原因");
                }
            }
            case CLIENT_ACTION -> {
                if (clientAction == null) {
                    throw new IllegalArgumentException("CLIENT_ACTION 必须提供客户端动作");
                }
                if (hasText(template) || hasText(unavailableReason)) {
                    throw new IllegalArgumentException("CLIENT_ACTION 不能携带 Agent 模板或不可用原因");
                }
            }
            case UNAVAILABLE -> {
                if (!hasText(unavailableReason)) {
                    throw new IllegalArgumentException("UNAVAILABLE 必须提供明确原因");
                }
                if (clientAction != null || hasText(template)) {
                    throw new IllegalArgumentException("UNAVAILABLE 不能携带执行动作或 Agent 模板");
                }
            }
        }
    }

    /**
     * 创建内置 Agent prompt 命令。
     */
    public static CommandInfo builtin(String name, String description, String template) {
        return agentPrompt(name, description, template, false, CommandSource.BUILTIN);
    }

    /**
     * 创建内置 Agent prompt 命令（带子任务标记）。
     */
    public static CommandInfo builtinSubtask(String name, String description, String template) {
        return agentPrompt(name, description, template, true, CommandSource.BUILTIN);
    }

    /**
     * 创建客户端动作命令。
     */
    public static CommandInfo clientAction(String name, String description, ClientAction action, String... aliases) {
        return new CommandInfo(name, description, null, null, null, false,
            CommandSource.BUILTIN, List.of(), immutableAliases(aliases),
            CommandDispatch.CLIENT_ACTION, action, null);
    }

    /**
     * 创建明确不可用的命令。该命令不会出现在可用目录中，也不会回退为 Agent prompt。
     */
    public static CommandInfo unavailable(String name, String description, String reason, String... aliases) {
        return new CommandInfo(name, description, null, null, null, false,
            CommandSource.BUILTIN, List.of(), immutableAliases(aliases),
            CommandDispatch.UNAVAILABLE, null, reason);
    }

    /**
     * 创建配置命令。用户配置、Markdown、MCP 和 Skill 命令都只能生成 Agent prompt。
     */
    public static CommandInfo fromConfig(String name, String description, String template,
                                         String agent, String model, boolean subtask) {
        return new CommandInfo(name, description, template, agent, model, subtask,
            CommandSource.CONFIG, extractHints(template), List.of(),
            CommandDispatch.AGENT_PROMPT, null, null);
    }

    /**
     * 从模板中提取参数提示。
     */
    public static List<String> extractHints(String template) {
        if (template == null) return List.of();
        List<String> hints = new java.util.ArrayList<>();

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(template);
        java.util.Set<String> numbered = new java.util.TreeSet<>();
        while (matcher.find()) {
            numbered.add(matcher.group());
        }
        hints.addAll(numbered);

        if (template.contains("$ARGUMENTS")) {
            hints.add("$ARGUMENTS");
        }

        return List.copyOf(hints);
    }

    /**
     * 替换 Agent prompt 模板中的参数。
     */
    public String resolveTemplate(String arguments) {
        if (template == null || arguments == null) return template;

        String resolved = template.replace("$ARGUMENTS", arguments);
        String[] args = arguments.split("\\s+", -1);
        for (int i = 0; i < args.length; i++) {
            resolved = resolved.replace("$" + (i + 1), args[i]);
        }
        return resolved;
    }

    /**
     * 获取命令的显示名称（包含别名）。
     */
    public String getDisplayName() {
        if (!aliases.isEmpty()) {
            return "/" + name + " (别名: " + String.join(", ", aliases) + ")";
        }
        return "/" + name;
    }

    private static CommandInfo agentPrompt(String name, String description, String template,
                                           boolean subtask, CommandSource source) {
        return new CommandInfo(name, description, template, null, null, subtask,
            source, extractHints(template), List.of(), CommandDispatch.AGENT_PROMPT, null, null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    private static List<String> immutableAliases(String... aliases) {
        if (aliases == null || aliases.length == 0) return List.of();
        return Arrays.stream(aliases)
            .filter(alias -> alias != null && !alias.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }
}