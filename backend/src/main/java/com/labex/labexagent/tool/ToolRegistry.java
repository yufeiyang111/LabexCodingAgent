package com.labex.labexagent.tool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final Map<String, AgentTool> dynamicTools = new ConcurrentHashMap<>();

    /** 每个模式允许使用的工具白名单（参考 OpenCode 的 Permission.Ruleset） */
    private static final Map<String, Set<String>> MODE_ALLOWED_TOOLS = Map.of(
        // plan 模式：只读 + plan_exit 切换
        "plan", Set.of(
            "read_file", "read", "glob", "grep", "list_files", "list",
            "project_overview", "repo_overview", "repo_map", "lsp", "lsp_symbols", "retrieve_context",
            "web_fetch", "webfetch", "web_search", "websearch", "understand_image", "image",
            "diagnostics", "create_plan", "plan_exit", "question", "skill", "mcp_call", "task", "context_note"
        ),
        // explore 模式：只读
        "explore", Set.of(
            "read_file", "read", "glob", "grep", "list_files", "list",
            "project_overview", "repo_overview", "repo_map", "lsp", "lsp_symbols", "retrieve_context",
            "web_fetch", "webfetch", "web_search", "websearch", "understand_image", "image",
            "diagnostics", "question", "context_note"
        )
        // build 模式：不限制（默认，不在此 Map 中）
    );

    /** 禁用的工具（不注入系统提示词，不注册到 LLM） */
    private static final Set<String> DISABLED_TOOLS = Set.of(
        "repo_clone",        // 已禁用，始终返回失败
        "external_directory" // 已禁用，始终返回失败
    );

    public ToolRegistry(List<AgentTool> agentTools) {
        for (AgentTool tool : agentTools) {
            String name = tool.definition().getName();
            if (!DISABLED_TOOLS.contains(name)) {
                this.tools.put(name, tool);
            }
        }
    }

    public AgentTool get(String name) {
        AgentTool tool = dynamicTools.get(name);
        if (tool != null) return tool;
        return this.tools.get(name);
    }

    /** 获取所有工具定义（用于 build 模式） */
    public Collection<ToolDefinition> definitions() {
        List<ToolDefinition> all = new ArrayList<>();
        this.tools.values().stream().map(AgentTool::definition).forEach(all::add);
        this.dynamicTools.values().stream().map(AgentTool::definition).forEach(all::add);
        return all;
    }

    /** 按模式过滤工具定义（用于 plan/explore 模式） */
    public Collection<ToolDefinition> definitionsForMode(String mode) {
        Set<String> allowed = MODE_ALLOWED_TOOLS.get(mode);
        if (allowed == null) {
            // build 模式或其他未定义模式：返回所有工具
            return definitions();
        }
        List<ToolDefinition> result = new ArrayList<>();
        this.tools.values().stream()
            .map(AgentTool::definition)
            .filter(d -> allowed.contains(d.getName()))
            .forEach(result::add);
        // MCP 动态工具在 build 模式下全部可用
        this.dynamicTools.values().stream()
            .map(AgentTool::definition)
            .forEach(result::add);
        return result;
    }

    /** 检查某工具在某模式下是否允许使用 */
    public boolean isToolAllowed(String mode, String toolName) {
        // 动态工具（MCP）在所有模式下都允许
        if (dynamicTools.containsKey(toolName)) return true;
        Set<String> allowed = MODE_ALLOWED_TOOLS.get(mode);
        if (allowed == null) return true; // build 模式不限制
        return allowed.contains(toolName);
    }

    // ===== 动态工具管理 =====

    /** 注册动态工具（MCP 工具等） */
    public void registerDynamicTool(String name, AgentTool tool) {
        dynamicTools.put(name, tool);
    }

    /** 注册多个动态工具 */
    public void registerDynamicTools(Map<String, AgentTool> tools) {
        dynamicTools.putAll(tools);
    }

    /** 移除动态工具 */
    public void removeDynamicTool(String name) {
        dynamicTools.remove(name);
    }

    /** 清除所有动态工具 */
    public void clearDynamicTools() {
        dynamicTools.clear();
    }

    /** 获取动态工具数量 */
    public int getDynamicToolCount() {
        return dynamicTools.size();
    }
}
