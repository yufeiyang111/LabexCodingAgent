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
    void rejectsNativeToolThatWasNotExposedEvenWhenArgumentsAreValid() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(tool("write_file", calls)));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);

        var admission = executor.resolveNative(context,
                new AgentModelTurnExecutor.NativeToolCall("write_file", "{}", "call-write", 0),
                "zh");

        assertFalse(admission.allowed());
        assertEquals("tool_not_available", admission.reasonCode());
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


    @Test
    void rejectsMalformedAndSchemaInvalidNativeArgumentsBeforeExecution() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(requiredStringTool("read_file", "file_path", calls)));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);

        var malformed = executor.resolveNative(context,
                new AgentModelTurnExecutor.NativeToolCall("read_file", "{\"file_path\":", "call-malformed", 0),
                "zh");
        var missing = executor.resolveNative(context,
                new AgentModelTurnExecutor.NativeToolCall("read_file", "{}", "call-missing", 1),
                "zh");

        assertFalse(malformed.allowed());
        assertEquals("invalid_json", malformed.reasonCode());
        assertTrue(malformed.arguments().isEmpty());
        assertFalse(missing.allowed());
        assertEquals("missing_required", missing.reasonCode());
        assertEquals(0, calls.get());
    }

    @Test
    void admitsValidNativeArgumentsWithTheOriginalStructuredObject() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(requiredStringTool("read_file", "file_path", calls)));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);

        var admission = executor.resolveNative(context,
                new AgentModelTurnExecutor.NativeToolCall(
                        "read_file", "{\"file_path\":\"README.md\"}", "call-read", 0),
                "zh");

        assertTrue(admission.allowed());
        assertEquals("ok", admission.reasonCode());
        assertEquals("README.md", admission.arguments().get("file_path").getAsString());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsRecoveredTextCallBeforeExecutionWhenArgumentsDoNotMatchExposedSchema() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(requiredStringTool("read_file", "file_path", calls)));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", "whoami");

        var resolution = executor.resolveRecovered(context, "read_file", arguments, "zh");

        assertFalse(resolution.allowed());
        assertTrue(resolution.rejection().getContent().contains("参数"));
        assertEquals(0, calls.get());
    }
    private AgentContext context() {
        AgentContext context = new AgentContext(null, null, null, null, null, null, List.of(), 0);
        context.setMode("build"); return context;
    }


    private AgentTool requiredStringTool(String name, String requiredField, AtomicInteger calls) {
        return new AgentTool() {
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(name).description(name)
                        .stringProperty(requiredField, requiredField, true).build();
            }
            public ToolResult execute(AgentContext context, JsonObject args) {
                calls.incrementAndGet();
                return ToolResult.ok("ok");
            }
        };
    }
    private AgentTool tool(String name, AtomicInteger calls) {
        return new AgentTool() {
            public ToolDefinition definition() { return ToolDefinition.builder().name(name).description(name).build(); }
            public ToolResult execute(AgentContext context, JsonObject args) { calls.incrementAndGet(); return ToolResult.ok("ok"); }
        };
    }
}
