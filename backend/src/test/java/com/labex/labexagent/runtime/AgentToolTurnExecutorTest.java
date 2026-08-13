package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolRegistry;
import com.labex.labexagent.tool.ToolResult;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        var resolution = executor.resolve(context, "read_file", "zh");

        ToolResult result = executor.execute(resolution.tool(), context, new JsonObject(), "read_file");

        assertTrue(result.isSuccess());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsExecutionWithoutActiveFenceBeforeCallingTheTool() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(tool("read_file", calls)));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        var resolution = executor.resolve(context, "read_file", "zh");

        assertThatThrownBy(() -> executor.execute(resolution.tool(), context, new JsonObject(), "read_file"))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsExecutionWhenLeaseIsStaleBeforeCallingTheTool() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(tool("read_file", calls)));
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        executor.setExecutionLeaseService(leases);
        var resolution = executor.resolve(context, "read_file", "zh");

        assertThatThrownBy(() -> executor.execute(resolution.tool(), context, new JsonObject(), "read_file"))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);
        assertEquals(0, calls.get());
    }

    @Test
    void executesToolWhenContextCarriesTheActiveFence() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(tool("read_file", calls)));
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        executor.setExecutionLeaseService(leases);
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

    @Test
    void bindsTheToolCallIdOnTheExecutionThreadAndClearsItAfterwards() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> seenToolCallId = new AtomicReference<>("unset");
        ToolRegistry registry = new ToolRegistry(List.of(new AgentTool() {
            public ToolDefinition definition() {
                return ToolDefinition.builder().name("read_file").description("read").build();
            }
            public ToolResult execute(AgentContext context, JsonObject args) {
                calls.incrementAndGet();
                seenToolCallId.set(AgentToolTurnExecutor.currentToolCallId());
                return ToolResult.ok("ok");
            }
        }));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        var resolution = executor.resolve(context, "read_file", "zh");

        ToolResult result = executor.execute(resolution.tool(), context, new JsonObject(), "read_file", "call-42");

        assertTrue(result.isSuccess());
        assertEquals(1, calls.get());
        assertEquals("call-42", seenToolCallId.get());
        assertEquals(null, AgentToolTurnExecutor.currentToolCallId());
    }

    @Test
    void doesNotLeakTheToolCallIdBindingAcrossExecutions() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> seenToolCallId = new AtomicReference<>("unset");
        ToolRegistry registry = new ToolRegistry(List.of(new AgentTool() {
            public ToolDefinition definition() {
                return ToolDefinition.builder().name("read_file").description("read").build();
            }
            public ToolResult execute(AgentContext context, JsonObject args) {
                calls.incrementAndGet();
                seenToolCallId.set(AgentToolTurnExecutor.currentToolCallId());
                return ToolResult.ok("ok");
            }
        }));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        var resolution = executor.resolve(context, "read_file", "zh");

        executor.execute(resolution.tool(), context, new JsonObject(), "read_file", "call-1");
        ToolResult second = executor.execute(resolution.tool(), context, new JsonObject(), "read_file");

        assertEquals(2, calls.get());
        assertEquals(null, seenToolCallId.get());
        assertTrue(second.isSuccess());
    }

    @Test
    void rejectsExecutionWithoutActiveFenceBeforeBindingTheToolCallId() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(tool("read_file", calls)));
        AgentContext context = context();
        context.setSelectedToolNames(List.of("read_file"));
        AgentToolTurnExecutor executor = new AgentToolTurnExecutor(registry);
        var resolution = executor.resolve(context, "read_file", "zh");

        assertThatThrownBy(() -> executor.execute(resolution.tool(), context, new JsonObject(), "read_file", "call-x"))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);
        assertEquals(0, calls.get());
        assertEquals(null, AgentToolTurnExecutor.currentToolCallId());
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
