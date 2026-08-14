package com.labex.labexagent.tool;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryModeTest {

    @Test
    void allowsDurableToolOutputReadsInPlanAndExploreModes() {
        AgentTool tool = tool("read_tool_output");
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertTrue(registry.isToolAllowed("plan", "read_tool_output"));
        assertTrue(registry.isToolAllowed("explore", "read_tool_output"));
        assertTrue(registry.definitionsForMode("plan").stream()
                .anyMatch(definition -> "read_tool_output".equals(definition.getName())));
    }

    @Test
    void unknownModesExposeNoToolsAndCannotExecuteRegisteredTools() {
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name("read_file").description("read").build();
            }

            @Override
            public ToolResult execute(com.labex.labexagent.runtime.AgentContext context, com.google.gson.JsonObject args) {
                return ToolResult.ok("ok");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertTrue(registry.definitionsForMode("unknown").isEmpty());
        assertFalse(registry.isToolAllowed("unknown", "read_file"));
        assertTrue(registry.isToolAllowed("build", "read_file"));
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(name).description(name).build();
            }

            @Override
            public ToolResult execute(com.labex.labexagent.runtime.AgentContext context, com.google.gson.JsonObject args) {
                return ToolResult.ok("ok");
            }
        };
    }
}
