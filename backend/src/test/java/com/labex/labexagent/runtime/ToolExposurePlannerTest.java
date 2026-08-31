package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import com.labex.labexagent.mcp.McpManager;
import com.labex.labexagent.mcp.McpToolAdapter;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.websearch.WebSearchProviderSelector;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.labexagent.tool.ToolResult;
import com.labex.service.AgentSkillService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolExposurePlannerTest {

    @Test
    void nativeExposureUsesStudentScopedMcpToolsInsteadOfTheGlobalDynamicRegistry() {
        ToolRegistry registry = registry();
        registry.registerDynamicTool("mcp_shared", tool("mcp_shared"));
        AgentSkillService skills = mock(AgentSkillService.class);
        when(skills.hasEnabledSkills(7)).thenReturn(true);
        McpManager mcp = mock(McpManager.class);
        when(mcp.getAvailableTools(7)).thenReturn(List.of(mcpTool("docs", "Docs", "search")));
        ToolExposurePlanner planner = new ToolExposurePlanner(registry, new com.labex.labexagent.tool.ToolSelectionPolicy(),
                skills, mcp, availableWebSearch());

        ToolExposure exposure = planner.plan(new ToolExposurePlanner.Request(
                7, "build", AgentRuntimeProfile.LABEX_NATIVE, true, true, true));

        assertEquals(List.of(
                "read_file", "read_tool_output", "glob", "grep", "write_file", "apply_patch", "shell",
                "todo_write", "question", "web_search", "web_fetch", "understand_image", "lsp", "skill", "task", "mcp_docs_search"),
                exposure.definitions().stream().map(ToolDefinition::getName).toList());
        assertTrue(exposure.scopedTools().containsKey("mcp_docs_search"));
        assertFalse(exposure.scopedTools().containsKey("mcp_shared"));
        assertFalse(exposure.definitions().stream().map(ToolDefinition::getName).anyMatch("mcp_shared"::equals));
        assertFalse(exposure.definitions().stream().map(ToolDefinition::getName).anyMatch("edit_file"::equals));
        assertTrue(exposure.definitions().stream().map(ToolDefinition::getName).anyMatch("apply_patch"::equals));
        verify(mcp).getAvailableTools(7);
    }

    @Test
    void nativeExposureHidesUnavailableWebSearchButKeepsWebFetch() {
        ToolRegistry registry = registry();
        AgentSkillService skills = mock(AgentSkillService.class);
        McpManager mcp = mock(McpManager.class);
        WebSearchProviderSelector webSearch = mock(WebSearchProviderSelector.class);
        when(webSearch.isAvailable()).thenReturn(false);
        ToolExposurePlanner planner = new ToolExposurePlanner(registry,
                new com.labex.labexagent.tool.ToolSelectionPolicy(), skills, mcp, webSearch);

        ToolExposure exposure = planner.plan(new ToolExposurePlanner.Request(
                7, "build", AgentRuntimeProfile.LABEX_NATIVE, false, true, true));
        List<String> names = exposure.definitions().stream().map(ToolDefinition::getName).toList();

        assertFalse(names.contains("web_search"));
        assertTrue(names.contains("web_fetch"));
    }
    @Test
    void restoringHistoricalNativeSnapshotKeepsItsOriginalEditSchema() {
        ToolExposurePlanner planner = new ToolExposurePlanner(registry(), new com.labex.labexagent.tool.ToolSelectionPolicy(),
                mock(AgentSkillService.class), mock(McpManager.class), availableWebSearch());
        ToolExposureSnapshot historical = ToolExposureSnapshot.live(AgentRuntimeProfile.LABEX_NATIVE, "build", List.of(
                tool("read_file").definition(), tool("edit_file").definition(), tool("write_file").definition(),
                tool("shell").definition()), List.of());

        ToolExposure restored = planner.restore(historical);

        assertTrue(restored.restoredFromSnapshot());
        assertEquals(List.of("read_file", "edit_file", "write_file", "shell"),
                restored.definitions().stream().map(ToolDefinition::getName).toList());
    }

    @Test
    void restoringAnExposureSnapshotRebuildsTheSameScopedMcpToolWithoutRediscovery() {
        ToolRegistry registry = registry();
        AgentSkillService skills = mock(AgentSkillService.class);
        McpManager mcp = mock(McpManager.class);
        when(mcp.getAvailableTools(7)).thenReturn(List.of(mcpTool("docs", "Docs", "search")));
        ToolExposurePlanner planner = new ToolExposurePlanner(registry, new com.labex.labexagent.tool.ToolSelectionPolicy(),
                skills, mcp, availableWebSearch());
        ToolExposure initial = planner.plan(new ToolExposurePlanner.Request(
                7, "build", AgentRuntimeProfile.LABEX_NATIVE, false, true, true));
        clearInvocations(mcp);

        ToolExposure restored = planner.restore(initial.snapshot());

        assertEquals(initial.snapshot().schemaFingerprint(), restored.snapshot().schemaFingerprint());
        assertEquals(initial.definitions().stream().map(ToolDefinition::getName).toList(),
                restored.definitions().stream().map(ToolDefinition::getName).toList());
        assertTrue(restored.scopedTools().containsKey("mcp_docs_search"));
        assertTrue(restored.restoredFromSnapshot());
        verifyNoInteractions(mcp);
    }

    private static WebSearchProviderSelector availableWebSearch() {
        WebSearchProviderSelector selector = mock(WebSearchProviderSelector.class);
        when(selector.isAvailable()).thenReturn(true);
        return selector;
    }
    private static ToolRegistry registry() {
        return new ToolRegistry(List.of(
                tool("read_file"), tool("read_tool_output"), tool("glob"), tool("grep"),
                tool("edit_file"), tool("write_file"), tool("apply_patch"), tool("shell"), tool("todo_write"), tool("question"),
                tool("web_search"), tool("web_fetch"), tool("understand_image"), tool("lsp"), tool("skill"),
                tool("task"), tool("mcp_call"), tool("create_plan"), tool("plan_exit"), tool("run_tests")));
    }

    private static McpToolAdapter.McpToolInfo mcpTool(String serverKey, String serverName, String toolName) {
        return new McpToolAdapter.McpToolInfo(serverKey, serverName, toolName, "Search docs",
                JsonParser.parseString("""
                        {"type":"object","properties":{"query":{"type":"string"},
                        "limit":{"type":"integer"}},"required":["query"]}
                        """).getAsJsonObject());
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(name).description(name).build();
            }

            @Override
            public ToolResult execute(AgentContext context, com.google.gson.JsonObject args) {
                return ToolResult.ok("ok");
            }
        };
    }
}
