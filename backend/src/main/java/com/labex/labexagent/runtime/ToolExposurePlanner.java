package com.labex.labexagent.runtime;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.mcp.McpManager;
import com.labex.labexagent.mcp.McpToolAdapter;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.labexagent.tool.ToolSelectionPolicy;
import com.labex.service.AgentSkillService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 为单个 native Provider 请求构造最小工具 schema，并将用户级 MCP 工具绑定限制在该运行上下文。
 */
@Slf4j
@Service
public final class ToolExposurePlanner {
    private final ToolRegistry toolRegistry;
    private final ToolSelectionPolicy toolSelectionPolicy;
    private final AgentSkillService skillService;
    private final McpManager mcpManager;

    public ToolExposurePlanner(ToolRegistry toolRegistry, ToolSelectionPolicy toolSelectionPolicy,
                               AgentSkillService skillService, McpManager mcpManager) {
        this.toolRegistry = toolRegistry;
        this.toolSelectionPolicy = toolSelectionPolicy;
        this.skillService = skillService;
        this.mcpManager = mcpManager;
    }

    public ToolExposure plan(Request request) {
        Request safeRequest = request == null ? Request.empty() : request;
        AgentRuntimeProfile profile = safeRequest.runtimeProfile();
        String mode = AgentMode.normalize(safeRequest.mode());
        boolean nativeProfile = profile == AgentRuntimeProfile.LABEX_NATIVE;
        boolean skillCatalogAvailable = nativeProfile && hasEnabledSkills(safeRequest.studentId());
        // native MCP 必须由当前 student 的实际发现结果构成，不能读取全局 dynamic registry。
        boolean globalDynamicMcpEnabled = !nativeProfile && toolRegistry != null && toolRegistry.getDynamicToolCount() > 0;
        ToolSelectionPolicy.Capabilities capabilities = new ToolSelectionPolicy.Capabilities(
                safeRequest.imageInputEnabled(), globalDynamicMcpEnabled,
                safeRequest.webSearchEnabled(), safeRequest.webFetchEnabled(), skillCatalogAvailable);
        List<ToolDefinition> definitions = new ArrayList<>(toolSelectionPolicy.select(
                toolRegistry, mode, capabilities, profile));
        List<McpToolAdapter.McpToolInfo> mcpDefinitions = nativeProfile && AgentMode.isUnrestricted(mode)
                ? discoverNativeMcpTools(safeRequest.studentId(), definitions) : List.of();
        Map<String, AgentTool> scopedTools = scopedMcpTools(mcpDefinitions);
        definitions.addAll(mcpDefinitions.stream()
                .map(info -> new McpToolAdapter(info, mcpManager).definition())
                .sorted(Comparator.comparing(ToolDefinition::getName))
                .toList());
        ToolExposureSnapshot snapshot = ToolExposureSnapshot.live(profile, mode, definitions, mcpDefinitions);
        return new ToolExposure(definitions, scopedTools, snapshot, false);
    }

    public ToolExposure restore(ToolExposureSnapshot snapshot) {
        if (snapshot == null) {
            return plan(Request.empty());
        }
        return new ToolExposure(snapshot.toToolDefinitions(), snapshot.toScopedTools(mcpManager), snapshot, true);
    }

    private boolean hasEnabledSkills(Integer studentId) {
        if (skillService == null || studentId == null) {
            return false;
        }
        try {
            return skillService.hasEnabledSkills(studentId);
        } catch (RuntimeException failure) {
            log.warn("Unable to resolve Skill capability for student {}: {}", studentId, failure.getMessage());
            return false;
        }
    }

    private List<McpToolAdapter.McpToolInfo> discoverNativeMcpTools(Integer studentId,
                                                                      List<ToolDefinition> staticDefinitions) {
        if (mcpManager == null || studentId == null) {
            return List.of();
        }
        try {
            Set<String> occupiedNames = new HashSet<>();
            for (ToolDefinition definition : staticDefinitions == null ? List.<ToolDefinition>of() : staticDefinitions) {
                if (definition != null && definition.getName() != null) {
                    occupiedNames.add(definition.getName());
                }
            }
            LinkedHashMap<String, McpToolAdapter.McpToolInfo> result = new LinkedHashMap<>();
            for (McpToolAdapter.McpToolInfo info : mcpManager.getAvailableTools(studentId)) {
                if (info == null || info.getUniqueId() == null || info.getUniqueId().isBlank()
                        || occupiedNames.contains(info.getUniqueId())) {
                    continue;
                }
                result.putIfAbsent(info.getUniqueId(), info);
            }
            return result.values().stream()
                    .sorted(Comparator.comparing(McpToolAdapter.McpToolInfo::getUniqueId))
                    .toList();
        } catch (RuntimeException discoveryFailure) {
            log.warn("Unable to discover MCP tools for student {}: {}", studentId, discoveryFailure.getMessage());
            return List.of();
        }
    }

    private Map<String, AgentTool> scopedMcpTools(List<McpToolAdapter.McpToolInfo> definitions) {
        if (definitions == null || definitions.isEmpty() || mcpManager == null) {
            return Map.of();
        }
        LinkedHashMap<String, AgentTool> result = new LinkedHashMap<>();
        for (McpToolAdapter.McpToolInfo info : definitions) {
            result.putIfAbsent(info.getUniqueId(), new McpToolAdapter(info, mcpManager));
        }
        return Map.copyOf(result);
    }

    public record Request(Integer studentId, String mode, AgentRuntimeProfile runtimeProfile,
                          boolean imageInputEnabled, boolean webSearchEnabled, boolean webFetchEnabled) {
        public Request {
            mode = AgentMode.normalize(mode);
            runtimeProfile = runtimeProfile == null ? AgentRuntimeProfile.LABEX_LEGACY : runtimeProfile;
        }

        static Request empty() {
            return new Request(null, "build", AgentRuntimeProfile.LABEX_LEGACY, false, false, false);
        }
    }
}
