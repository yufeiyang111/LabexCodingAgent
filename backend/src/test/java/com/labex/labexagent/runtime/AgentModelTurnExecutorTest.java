package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.ProviderCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentModelTurnExecutorTest {
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @AfterEach
    void closeExecutor() {
        executorService.shutdownNow();
    }

    @Test
    void assemblesThinkingTextUsageAndDurableEvents() throws Exception {
        List<String> events = new ArrayList<>();
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 1000);
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE, callback -> {
            callback.accept(chunk("thinking_delta", "检查", null, null, null));
            callback.accept(chunk("text_delta", "完成", null, null, null));
            callback.accept(chunk("done", "", null, null, Map.of("total_tokens", 12)));
        });

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request(provider, events));

        assertEquals(AgentModelTurnExecutor.ResultType.TEXT, result.type());
        assertEquals("完成", result.content());
        assertEquals("检查", result.thinking());
        assertEquals(12, result.usage().get("total_tokens"));
        assertEquals(List.of("THINK_START", "THINK_DELTA", "FINAL_DELTA", "THINK"), events);
    }

    @Test
    void preservesAllNativeToolCallsInProviderOrder() throws Exception {
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 1000);
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE, callback -> {
            callback.accept(chunk("tool_call", "", "read_file", "{\"file_path\":\"a.txt\"}", Map.of("total_tokens", 4), "native-1", 0));
            callback.accept(chunk("tool_call", "", "list_files", "{}", null, "native-2", 1));
            callback.accept(chunk("done", "", null, null, null));
        });

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request(provider, new ArrayList<>()));

        assertEquals(AgentModelTurnExecutor.ResultType.TOOL_CALL, result.type());
        assertEquals(2, result.toolCalls().size());
        assertEquals("read_file", result.toolCalls().get(0).toolName());
        assertEquals("native-1", result.toolCalls().get(0).toolCallId());
        assertEquals(0, result.toolCalls().get(0).toolCallIndex());
        assertEquals("list_files", result.toolCalls().get(1).toolName());
        assertEquals("native-2", result.toolCalls().get(1).toolCallId());
        assertEquals(1, result.toolCalls().get(1).toolCallIndex());
        assertEquals(2, ((List<?>) result.toMap().get("toolCalls")).size());
    }

    @Test
    void rejectsAProviderStreamThatEndsWithoutATerminalEvent() throws Exception {
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 1000);
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE, callback -> {
            callback.accept(chunk("text_delta", "partial response", null, null, null));
        });

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request(provider, new ArrayList<>()));

        assertEquals(AgentModelTurnExecutor.ResultType.ERROR, result.type());
        assertTrue(result.message().toLowerCase().contains("terminal event"));
        assertTrue(result.content().contains("partial response"));
    }

    @Test
    void rejectsUnsupportedCapabilitiesBeforeCallingProvider() throws Exception {
        boolean[] called = {false};
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 1000);
        LlmProvider provider = provider(ProviderCapabilities.NONE, callback -> called[0] = true);

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request(provider, new ArrayList<>()));

        assertEquals(AgentModelTurnExecutor.ResultType.ERROR, result.type());
        assertTrue(result.message().contains("streaming"));
        assertFalse(called[0]);
    }

    @Test
    void timesOutAndRequestsCancellation() throws Exception {
        boolean[] cancelled = {false};
        CancellationToken token = new CancellationToken() {
            @Override
            public boolean isCancellationRequested() { return cancelled[0]; }
            @Override
            public Registration onCancellation(Runnable listener) { return Registration.noop(); }
        };
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 20,
                ignored -> cancelled[0] = true);
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE, callback -> {
            try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });

        AgentModelTurnExecutor.ModelTurnRequest request = request(provider, new ArrayList<>()).withCancellationToken(token);
        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request);

        assertEquals(AgentModelTurnExecutor.ResultType.ERROR, result.type());
        assertTrue(result.message().contains("超时") || result.message().toLowerCase().contains("timed out"));
        assertTrue(cancelled[0]);
    }

    private AgentModelTurnExecutor.ModelTurnRequest request(LlmProvider provider, List<String> events) {
        return new AgentModelTurnExecutor.ModelTurnRequest(
                "system", List.of(Map.of("role", "user", "content", "hello")),
                List.of(Map.of("type", "function")), provider,
                new LlmProvider.LlmConfig("key", "https://example.com", "model", 100, 0.0),
                1, 99L, "zh", CancellationToken.none(),
                new AgentModelTurnExecutor.EventSink() {
                    @Override public void durable(String type, Object data) { events.add(type); }
                    @Override public void transientEvent(String type, Object data) { events.add(type); }
                });
    }

    private LlmProvider provider(ProviderCapabilities capabilities, Consumer<Consumer<LlmProvider.StreamChunk>> stream) {
        return new LlmProvider() {
            @Override public String getProviderId() { return "test"; }
            @Override public String getProviderName() { return "test"; }
            @Override public boolean supportsStreaming() { return capabilities.streaming(); }
            @Override public boolean supportsToolCalling() { return capabilities.toolCalling(); }
            @Override public ProviderCapabilities capabilities() { return capabilities; }
            @Override public Map<String, Object> chatWithTools(String systemPrompt, List<Map<String, Object>> messages,
                                                                List<Map<String, Object>> tools, LlmConfig config) {
                return Map.of();
            }
            @Override public void chatStream(String systemPrompt, List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools, LlmConfig config,
                                             Consumer<StreamChunk> callback) {
                stream.accept(callback);
            }
            @Override public void chatStream(String systemPrompt, List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools, LlmConfig config,
                                             CancellationToken cancellationToken, Consumer<StreamChunk> callback) {
                stream.accept(callback);
            }
        };
    }

    private LlmProvider.StreamChunk chunk(String type, String content, String tool, String args, Map<String, Object> usage) {
        return chunk(type, content, tool, args, usage, null, null);
    }

    private LlmProvider.StreamChunk chunk(String type, String content, String tool, String args,
                                          Map<String, Object> usage, String toolCallId, Integer toolCallIndex) {
        return new LlmProvider.StreamChunk(type, content, tool, args, null, false, usage,
                toolCallId, toolCallIndex, null);
    }
}
