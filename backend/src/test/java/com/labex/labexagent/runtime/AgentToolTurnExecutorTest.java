package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.labexagent.tool.ToolResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentToolTurnExecutorTest {
    @Test
    void rejectsToolThatWasNotSelectedWithoutCallingIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(tool("write_file", calls)));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);

        var resolution = executor.resolve(context, "write_file", "zh");

        assertFalse(resolution.allowed());
        assertEquals(0, calls.get());
    }

    @Test
    void executesResolvedSelectedToolExactlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(tool("read_file", calls)));
        AgentContext context = context(); context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        var resolution = executor.resolve(context, "read_file", "zh");

        ToolResult result = executor.execute(resolution.tool(), context, new JsonObject(), "read_file");

        assertTrue(result.isSuccess());
        assertEquals(1, calls.get());
    }

    private AgentContext context() {
        AgentContext context = new AgentContext(null, null, null, null, null, null, List.of(), List.of(), 0);
        context.setMode("build"); return context;
    }

    private AgentTool tool(String name, AtomicInteger calls) {
        return new AgentTool() {
            public ToolDefinition definition() { return ToolDefinition.builder().name(name).description(name).build(); }
            public ToolResult execute(AgentContext context, JsonObject args) { calls.incrementAndGet(); return ToolResult.ok("ok"); }
        };
    }
}
