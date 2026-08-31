package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.ProviderCapabilities;
import com.labex.labexagent.llm.ProviderFailure;
import com.labex.labexagent.llm.ProviderFailureType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals(List.of("THINK_START", "THINK_DELTA", "FINAL_CANDIDATE_DELTA", "THINK"), events);
    }

    @Test
    void enforcesReasoningBoundaryForEveryProviderImplementation() throws Exception {
        List<Map<String, Object>> projected = new ArrayList<>();
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 1000);
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE, callback -> {
            callback.accept(chunk("text_delta", "Visible <TH", null, null, null));
            callback.accept(chunk("text_delta", "INK data-kind='hidden'>private plan</THINK", null, null, null));
            callback.accept(chunk("text_delta", "ING> answer", null, null, null));
            callback.accept(chunk("done", "", null, null, null));
        });
        AgentModelTurnExecutor.ModelTurnRequest request = new AgentModelTurnExecutor.ModelTurnRequest(
                "system", List.of(Map.of("role", "user", "content", "hello")),
                List.of(Map.of("type", "function")), provider,
                new LlmProvider.LlmConfig("key", "https://example.com", "model", 100, 0.0),
                1, 99L, "zh", CancellationToken.none(),
                new AgentModelTurnExecutor.EventSink() {
                    @Override
                    public void durable(String type, Object data) {
                        projected.add(Map.of("type", type, "data", data));
                    }

                    @Override
                    public void transientEvent(String type, Object data) {
                        projected.add(Map.of("type", type, "data", data));
                    }
                });

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request);

        assertEquals("Visible  answer", result.content());
        assertEquals("private plan", result.thinking());
        assertFalse(projected.toString().toLowerCase().contains("<think"));
    }

    @Test
    void keepsExplicitTextToolEnvelopeOutOfVisibleFinalDeltas() throws Exception {
        List<String> events = new ArrayList<>();
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 1000);
        String envelope = "<tool_call>{\"name\":\"list_files\",\"arguments\":{}}</tool_call>";
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE, callback -> {
            callback.accept(chunk("text_delta", "<to", null, null, null));
            callback.accept(chunk("text_delta", envelope.substring(3), null, null, null));
            callback.accept(chunk("done", "", null, null, null));
        });

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request(provider, events));

        assertEquals(AgentModelTurnExecutor.ResultType.TEXT, result.type());
        assertEquals(envelope, result.content());
        assertFalse(events.contains("FINAL_CANDIDATE_DELTA"));
        assertFalse(events.contains("FINAL_DELTA"));
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
    void waitsForProviderCompletionWhenTheOuterWatchdogIsDisabled() throws Exception {
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 0L);
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE, callback -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            callback.accept(chunk("text_delta", "late but completed", null, null, null));
            callback.accept(chunk("done", "", null, null, null));
        });

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request(provider, new ArrayList<>()));

        assertEquals(AgentModelTurnExecutor.ResultType.TEXT, result.type());
        assertEquals("late but completed", result.content());
    }
    @Test
    void timesOutWithoutMarkingTheParentRunAsUserCancelled() throws Exception {
        AgentCancellationRegistry.ActiveRun parentRun = new AgentCancellationRegistry()
                .register("timeout-parent", 7, 12, 51L);
        AtomicReference<CancellationToken> providerToken = new AtomicReference<>();
        AtomicBoolean providerCancellationObserved = new AtomicBoolean();
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 20);
        LlmProvider provider = blockingProvider(providerToken, providerCancellationObserved);

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(
                request(provider, new ArrayList<>()).withCancellationToken(parentRun));

        assertEquals(AgentModelTurnExecutor.ResultType.ERROR, result.type());
        assertTrue(result.message().contains("超时") || result.message().toLowerCase().contains("timed out"));
        assertFalse(parentRun.isCancellationRequested());
        assertTrue(providerCancellationObserved.get());
        assertEquals("model_timeout", result.toMap().get("reasonCode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void surfacesProviderTransportDiagnosticsThroughTheErrorResult() throws Exception {
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 1000);
        Map<String, Object> diagnostics = Map.of(
                "endpoint", "https://example.com/v1/chat/completions",
                "model", "test-model",
                "failure_stage", "mid_stream",
                "elapsed_ms", 4_200L);
        LlmProvider.StreamChunk failureChunk = new LlmProvider.StreamChunk("error", "Read timed out", null, null,
                null, true, null, null, null,
                new ProviderFailure(ProviderFailureType.TIMEOUT, null, "Read timed out", true, diagnostics));
        LlmProvider provider = provider(ProviderCapabilities.OPENAI_COMPATIBLE,
                callback -> callback.accept(failureChunk));

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(request(provider, new ArrayList<>()));

        assertEquals(AgentModelTurnExecutor.ResultType.ERROR, result.type());
        assertEquals("provider_error", result.toMap().get("reasonCode"));
        Map<String, Object> surfaced = (Map<String, Object>) result.toMap().get("diagnostics");
        assertEquals("https://example.com/v1/chat/completions", surfaced.get("endpoint"));
        assertEquals("mid_stream", surfaced.get("failure_stage"));
        assertEquals(4_200L, surfaced.get("elapsed_ms"));
        assertEquals("timeout", surfaced.get("failure_type"));
        assertTrue(((Number) surfaced.get("partial_output_chars")).intValue() >= 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void annotatesWatchdogTimeoutsWithExecutorSideDiagnosticsWhenTheProviderStaysSilent() throws Exception {
        AgentCancellationRegistry.ActiveRun parentRun = new AgentCancellationRegistry()
                .register("watchdog-diag-parent", 7, 12, 51L);
        AtomicReference<CancellationToken> providerToken = new AtomicReference<>();
        AtomicBoolean providerCancellationObserved = new AtomicBoolean();
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(executorService, 20);
        LlmProvider provider = blockingProvider(providerToken, providerCancellationObserved);

        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(
                request(provider, new ArrayList<>()).withCancellationToken(parentRun));

        assertEquals(AgentModelTurnExecutor.ResultType.ERROR, result.type());
        Map<String, Object> surfaced = (Map<String, Object>) result.toMap().get("diagnostics");
        assertEquals("watchdog_total_timeout", surfaced.get("failure_type"));
        assertEquals(20L, surfaced.get("watchdog_total_timeout_ms"));
        assertTrue(((Number) surfaced.get("watchdog_elapsed_ms")).longValue() >= 15L);
    }

    private LlmProvider blockingProvider(AtomicReference<CancellationToken> providerToken,
                                         AtomicBoolean providerCancellationObserved) {
        return new LlmProvider() {
            @Override public String getProviderId() { return "test"; }
            @Override public String getProviderName() { return "test"; }
            @Override public boolean supportsStreaming() { return true; }
            @Override public boolean supportsToolCalling() { return true; }
            @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.OPENAI_COMPATIBLE; }
            @Override public Map<String, Object> chatWithTools(String systemPrompt, List<Map<String, Object>> messages,
                                                                List<Map<String, Object>> tools, LlmConfig config) {
                return Map.of();
            }
            @Override public void chatStream(String systemPrompt, List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools, LlmConfig config,
                                             Consumer<StreamChunk> callback) {
                throw new AssertionError("Executor must provide a cancellation token to the provider");
            }
            @Override public void chatStream(String systemPrompt, List<Map<String, Object>> messages,
                                             List<Map<String, Object>> tools, LlmConfig config,
                                             CancellationToken cancellationToken, Consumer<StreamChunk> callback) {
                providerToken.set(cancellationToken);
                try (CancellationToken.Registration ignored = cancellationToken.onCancellation(
                        () -> providerCancellationObserved.set(true))) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
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
