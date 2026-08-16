package com.labex.labexagent.tool;

import com.labex.labexagent.runtime.AgentMode;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据运行模式和运行时能力构建模型可见的最小原子工具集。
 *
 * <p>工具是否已注册与是否应进入某次模型 schema 是两件事：前者保留 durable replay、管理操作和
 * 内部服务能力，后者只暴露当前模式完成任务真正需要的原子能力。</p>
 */
@org.springframework.stereotype.Service
public final class ToolSelectionPolicy {
    private static final Set<String> IMAGE_TOOLS = Set.of("understand_image", "image");
    private static final Set<String> WEB_SEARCH_TOOLS = Set.of("web_search", "websearch");
    private static final Set<String> WEB_FETCH_TOOLS = Set.of("web_fetch", "webfetch");
    private static final Set<String> MCP_ENTRY_TOOLS = Set.of("mcp_call");
    /** native 运行时将这些 Harness 控制概念保留为 UI/持久化投影，不放入模型 schema。 */
    private static final Set<String> NATIVE_HARNESS_CONTROL_TOOLS = Set.of("todo_write", "plan_exit", "create_plan");

    /**
     * 默认 profile 故意不包含计划创建、验证、预览、RAG、项目摘要和配置提案等控制面工具。
     * 它们由 Harness 投影、统一 shell 或后续按需 capability profile 承担。
     */
    private static final Map<String, Set<String>> MODEL_VISIBLE_STATIC_TOOLS = Map.of(
            "build", Set.of(
                    "read_file", "read_tool_output", "glob", "grep", "edit_file", "write_file", "shell",
                    "todo_write", "question", "web_search", "web_fetch", "understand_image"),
            "plan", Set.of(
                    "read_file", "read_tool_output", "glob", "grep", "todo_write", "plan_exit", "question",
                    "web_search", "web_fetch", "understand_image"),
            "explore", Set.of(
                    "read_file", "read_tool_output", "glob", "grep", "question", "web_search", "web_fetch",
                    "understand_image"));

    public List<ToolDefinition> select(ToolRegistry registry, String mode, Capabilities capabilities) {
        return select(registry, mode, capabilities, AgentRuntimeProfile.LABEX_LEGACY);
    }

    /**
     * 按 conversation/task 的运行时 profile 构建本轮 schema；注册表不变，只有模型可见集合变化。
     */
    public List<ToolDefinition> select(ToolRegistry registry, String mode, Capabilities capabilities,
                                       AgentRuntimeProfile runtimeProfile) {
        if (registry == null || !AgentMode.isSupported(mode)) {
            return List.of();
        }
        Set<String> profile = MODEL_VISIBLE_STATIC_TOOLS.get(mode);
        if (profile == null) {
            return List.of();
        }
        AgentRuntimeProfile effectiveRuntimeProfile = runtimeProfile == null
                ? AgentRuntimeProfile.LABEX_LEGACY : runtimeProfile;
        Capabilities safeCapabilities = capabilities == null ? Capabilities.none() : capabilities;
        List<ToolDefinition> selected = new ArrayList<>();
        Collection<ToolDefinition> staticDefinitions = registry.staticDefinitionsForMode(mode);
        for (ToolDefinition definition : staticDefinitions == null ? List.<ToolDefinition>of() : staticDefinitions) {
            if (profile.contains(definition.getName())
                    && isCapabilityAvailable(definition.getName(), safeCapabilities)
                    && isVisibleInRuntimeProfile(definition.getName(), effectiveRuntimeProfile)) {
                selected.add(definition);
            }
        }
        if (safeCapabilities.mcpEnabled() && AgentMode.isUnrestricted(mode)) {
            Collection<ToolDefinition> dynamicDefinitions = registry.dynamicDefinitions();
            (dynamicDefinitions == null ? java.util.stream.Stream.<ToolDefinition>empty() : dynamicDefinitions.stream())
                    .filter(definition -> isCapabilityAvailable(definition.getName(), safeCapabilities))
                    .sorted(java.util.Comparator.comparing(ToolDefinition::getName))
                    .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    public boolean isSelected(Collection<ToolDefinition> selectedDefinitions, String toolName) {
        if (toolName == null || selectedDefinitions == null) return false;
        return selectedDefinitions.stream().anyMatch(definition -> toolName.equals(definition.getName()));
    }

    public Set<String> selectedNames(Collection<ToolDefinition> selectedDefinitions) {
        if (selectedDefinitions == null || selectedDefinitions.isEmpty()) return Set.of();
        Set<String> names = new HashSet<>();
        selectedDefinitions.forEach(definition -> names.add(definition.getName()));
        return Set.copyOf(names);
    }

    private boolean isVisibleInRuntimeProfile(String toolName, AgentRuntimeProfile runtimeProfile) {
        return runtimeProfile != AgentRuntimeProfile.LABEX_NATIVE
                || !NATIVE_HARNESS_CONTROL_TOOLS.contains(toolName);
    }

    private boolean isCapabilityAvailable(String toolName, Capabilities capabilities) {
        if (IMAGE_TOOLS.contains(toolName)) return capabilities.imageInputEnabled();
        if (MCP_ENTRY_TOOLS.contains(toolName)) return false;
        if (WEB_SEARCH_TOOLS.contains(toolName)) return capabilities.webSearchEnabled();
        if (WEB_FETCH_TOOLS.contains(toolName)) return capabilities.webFetchEnabled();
        return true;
    }

    public record Capabilities(boolean imageInputEnabled, boolean mcpEnabled,
                               boolean webSearchEnabled, boolean webFetchEnabled) {
        public static Capabilities none() {
            return new Capabilities(false, false, false, false);
        }
    }
}
