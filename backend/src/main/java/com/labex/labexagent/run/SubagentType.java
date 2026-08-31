package com.labex.labexagent.run;

import java.util.Locale;
import java.util.Set;

/**
 * 子代理类型画像：工具白名单与只读性，对齐 OpenCode 的 per-agent 权限模型
 * （参考 opencode-dev packages/opencode/src/agent/agent.ts 内建 general/explore 定义）。
 *
 * <p>差异点（经产品确认）：todowrite 对所有子代理开放；question 保持 OpenCode 默认 deny；
 * task 工具由深度门控单独决定，不进入固定白名单。</p>
 */
public enum SubagentType {
    EXPLORE,
    SCOUT,
    GENERAL;

    public boolean readOnly() {
        return this != GENERAL;
    }

    public String persisted() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SubagentType parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (SubagentType type : values()) {
            if (type.persisted().equals(normalized)) return type;
        }
        return GENERAL;
    }

    /** 该类型的最终模型可见工具白名单（不含 task；task 由深度门控追加）。 */
    public Set<String> allowedTools() {
        return switch (this) {
            case EXPLORE -> Set.of(
                    "read_file", "read_tool_output", "glob", "grep", "list_files",
                    "web_search", "web_fetch", "todo_write");
            case SCOUT -> Set.of(
                    "read_file", "read_tool_output", "glob", "grep",
                    "web_search", "web_fetch", "todo_write");
            case GENERAL -> Set.of(
                    "read_file", "read_tool_output", "glob", "grep", "list_files",
                    "edit_file", "write_file", "apply_patch", "shell",
                    "web_search", "web_fetch", "understand_image", "todo_write");
        };
    }
}
