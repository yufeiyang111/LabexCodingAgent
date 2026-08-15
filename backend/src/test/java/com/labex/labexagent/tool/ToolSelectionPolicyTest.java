package com.labex.labexagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolSelectionPolicyTest {

    @Test
    void selectsExactSchemasForEveryModeAndKeepsDynamicMcpToolsOutOfReadOnlyModes() {
        ToolRegistry registry = registry();
        registry.registerDynamicTool("mcp_weather", tool("mcp_weather"));
        ToolSelectionPolicy policy = new ToolSelectionPolicy();
        ToolSelectionPolicy.Capabilities all = new ToolSelectionPolicy.Capabilities(true, true, true, true);

        assertEquals(List.of(
                "read_file", "write_file", "understand_image", "web_search", "web_fetch",
                "mcp_call", "create_plan", "plan_exit", "question", "mcp_weather"),
                names(policy.select(registry, "build", all)));
        assertEquals(List.of(
                "read_file", "understand_image", "web_search", "web_fetch",
                "mcp_call", "create_plan", "plan_exit", "question"),
                names(policy.select(registry, "plan", all)));
        assertEquals(List.of(
                "read_file", "understand_image", "web_search", "web_fetch", "question"),
                names(policy.select(registry, "explore", all)));
        assertTrue(policy.select(registry, "unknown", all).isEmpty());
    }

    @Test
    void removesCapabilitySchemasWithoutChangingOtherBuildTools() {
        ToolRegistry registry = registry();
        registry.registerDynamicTool("mcp_weather", tool("mcp_weather"));
        ToolSelectionPolicy policy = new ToolSelectionPolicy();

        List<ToolDefinition> selected = policy.select(registry, "build",
                new ToolSelectionPolicy.Capabilities(false, false, false, false));

        assertEquals(List.of("read_file", "write_file", "create_plan", "plan_exit", "question"), names(selected));
        assertFalse(policy.isSelected(selected, "understand_image"));
        assertFalse(policy.isSelected(selected, "mcp_weather"));
        assertTrue(policy.isSelected(selected, "write_file"));
    }

    private static ToolRegistry registry() {
        return new ToolRegistry(List.of(
                tool("read_file"), tool("write_file"), tool("understand_image"),
                tool("web_search"), tool("web_fetch"),
                tool("mcp_call"), tool("create_plan"), tool("plan_exit"), tool("question")));
    }

    private static List<String> names(List<ToolDefinition> definitions) {
        return definitions.stream().map(ToolDefinition::getName).toList();
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(name).description(name).build();
            }

            @Override
            public ToolResult execute(AgentContext context, JsonObject args) {
                return ToolResult.ok("ok");
            }
        };
    }
}
