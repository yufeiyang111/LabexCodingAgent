package com.labex.labexagent.command;

import java.util.List;
import java.util.Map;

/**
 * 命令信息 - 工业级别的命令元数据
 * 完全复刻Opencode的命令配置结构
 */
public record CommandInfo(
    String name,                    // 命令名称
    String description,             // 命令描述
    String template,                // LLM提示模板
    String agent,                   // 指定的Agent
    String model,                   // 覆盖的模型
    boolean subtask,                // 是否作为子任务运行
    CommandSource source,           // 命令来源
    List<String> hints,             // 参数提示（$1, $2, $ARGUMENTS等）
    Map<String, String> aliases     // 命令别名
) {
    /**
     * 命令来源枚举
     */
    public enum CommandSource {
        BUILTIN,    // 内置命令
        CONFIG,     // JSON配置
        MARKDOWN,   // Markdown文件
        MCP,        // MCP服务器
        SKILL       // Skill插件
    }

    /**
     * 创建内置命令
     */
    public static CommandInfo builtin(String name, String description, String template) {
        return new CommandInfo(name, description, template, null, null, false,
            CommandSource.BUILTIN, extractHints(template), Map.of());
    }

    /**
     * 创建内置命令（带子任务标记）
     */
    public static CommandInfo builtinSubtask(String name, String description, String template) {
        return new CommandInfo(name, description, template, null, null, true,
            CommandSource.BUILTIN, extractHints(template), Map.of());
    }

    /**
     * 创建配置命令
     */
    public static CommandInfo fromConfig(String name, String description, String template,
                                         String agent, String model, boolean subtask) {
        return new CommandInfo(name, description, template, agent, model, subtask,
            CommandSource.CONFIG, extractHints(template), Map.of());
    }

    /**
     * 从模板中提取参数提示
     */
    public static List<String> extractHints(String template) {
        if (template == null) return List.of();
        List<String> hints = new java.util.ArrayList<>();

        // 提取 $1, $2, $3... 位置参数
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(template);
        java.util.Set<String> numbered = new java.util.TreeSet<>();
        while (matcher.find()) {
            numbered.add(matcher.group());
        }
        hints.addAll(numbered);

        // 检查 $ARGUMENTS
        if (template.contains("$ARGUMENTS")) {
            hints.add("$ARGUMENTS");
        }

        return List.copyOf(hints);
    }

    /**
     * 替换模板中的参数
     */
    public String resolveTemplate(String arguments) {
        if (template == null || arguments == null) return template;

        String resolved = template;

        // 替换 $ARGUMENTS
        resolved = resolved.replace("$ARGUMENTS", arguments);

        // 替换 $1, $2, $3... 位置参数
        String[] args = arguments.split("\\s+", -1);
        for (int i = 0; i < args.length; i++) {
            resolved = resolved.replace("$" + (i + 1), args[i]);
        }

        return resolved;
    }

    /**
     * 获取命令的显示名称（包含别名）
     */
    public String getDisplayName() {
        if (aliases != null && !aliases.isEmpty()) {
            return "/" + name + " (别名: " + String.join(", ", aliases.keySet()) + ")";
        }
        return "/" + name;
    }
}
