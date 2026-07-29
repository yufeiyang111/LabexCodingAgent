package com.labex.labexagent.tool;

import com.labex.labexagent.runtime.AgentMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 根据运行模式与模型/运行时能力生成唯一的工具 Schema 集合。 */
@org.springframework.stereotype.Service
public final class ToolSelectionPolicy {
    private static final Set<String> IMAGE_TOOLS = Set.of("understand_image", "image");
    private static final Set<String> WEB_SEARCH_TOOLS = Set.of("web_search", "websearch");
    private static final Set<String> WEB_FETCH_TOOLS = Set.of("web_fetch", "webfetch");
    private static final Set<String> MCP_ENTRY_TOOLS = Set.of("mcp_call");

    public List<ToolDefinition> select(ToolRegistry registry, String mode, Capabilities capabilities) {
        if (registry == null || !AgentMode.isSupported(mode)) {
            return List.of();
        }
        Capabilities safeCapabilities = capabilities == null ? Capabilities.none() : capabilities;
        List<ToolDefinition> selected = new ArrayList<>();
        Collection<ToolDefinition> staticDefinitions = registry.staticDefinitionsForMode(mode);
        for (ToolDefinition definition : staticDefinitions == null ? List.<ToolDefinition>of() : staticDefinitions) {
            if (isCapabilityAvailable(definition.getName(), safeCapabilities)) {
                selected.add(definition);
            }
        }
        if (safeCapabilities.mcpEnabled() && AgentMode.isUnrestricted(mode)) {
            Collection<ToolDefinition> dynamicDefinitions = registry.dynamicDefinitions();
            (dynamicDefinitions == null ? java.util.stream.Stream.<ToolDefinition>empty() : dynamicDefinitions.stream())
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

    private boolean isCapabilityAvailable(String toolName, Capabilities capabilities) {
        if (IMAGE_TOOLS.contains(toolName)) return capabilities.imageInputEnabled();
        if (MCP_ENTRY_TOOLS.contains(toolName)) return capabilities.mcpEnabled();
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
