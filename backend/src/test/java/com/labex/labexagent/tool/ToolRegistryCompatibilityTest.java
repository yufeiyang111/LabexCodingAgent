package com.labex.labexagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.impl.InvalidTool;
import com.labex.labexagent.tool.impl.ReadFileTool;
import com.labex.labexagent.tool.impl.TodoWriteTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryCompatibilityTest {

    @Test
    void compatibilityAliasesResolveForExecutionWithoutBeingModelVisibleAndInvalidStaysInternal() {
        AgentTool readFile = new ReadFileTool(null);
        AgentTool todoWrite = new TodoWriteTool(null);
        ToolRegistry registry = new ToolRegistry(List.of(readFile, todoWrite, new InvalidTool()));

        assertThat(registry.definitions()).extracting(ToolDefinition::getName)
                .containsExactly("read_file", "todo_write");
        assertThat(registry.get("read")).isSameAs(readFile);
        assertThat(registry.get("todo")).isSameAs(todoWrite);
        assertThat(registry.get("invalid")).isNull();
    }

    @Test
    void dynamicToolNameTakesPrecedenceOverAStaticCompatibilityAlias() {
        AgentTool staticReadFile = new ReadFileTool(null);
        AgentTool dynamicRead = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name("read").description("dynamic read")
                        .stringProperty("query", "query", true).build();
            }

            @Override
            public ToolResult execute(AgentContext context, JsonObject args) {
                return ToolResult.ok("dynamic");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(staticReadFile));
        registry.registerDynamicTool("read", dynamicRead);

        assertThat(registry.canonicalName("read")).isEqualTo("read");
        assertThat(registry.isCompatibilityAlias("read")).isFalse();
        assertThat(registry.get("read")).isSameAs(dynamicRead);
    }
}
