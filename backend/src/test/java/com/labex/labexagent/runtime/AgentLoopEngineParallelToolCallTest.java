package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.labex.labexagent.llm.LlmProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopEngineParallelToolCallTest {

    @Test
    void serializesProviderParallelToolCallsInsteadOfTurningThemIntoModelErrors() throws Exception {
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(5_000L);
        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(new AgentModelTurnExecutor.ModelTurnRequest(
                "system", List.of(), List.of(), new ParallelCallProvider(),
                new LlmProvider.LlmConfig("key", "https://example.test", "model", 32, 0.1),
                1, 71L, "en", CancellationToken.none(), new NoopSink()));
        assertEquals(AgentModelTurnExecutor.ResultType.TOOL_CALL, result.type());
        assertEquals(2, result.toolCalls().size());
        assertEquals("read_file", result.toolCalls().get(0).toolName());
        assertEquals("call-read", result.toolCalls().get(0).toolCallId());
        assertEquals("list_files", result.toolCalls().get(1).toolName());
        assertEquals("call-list", result.toolCalls().get(1).toolCallId());
    }

    private static final class NoopSink implements AgentModelTurnExecutor.EventSink {
        public void durable(String type, Object data) {}
        public void transientEvent(String type, Object data) {}
    }

    private static final class ParallelCallProvider implements LlmProvider {
        @Override public String getProviderId() { return "parallel-test"; }
        @Override public String getProviderName() { return "parallel-test"; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                                             List<Map<String, Object>> tools, LlmConfig config) {
            return Map.of();
        }
        @Override public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                                         List<Map<String, Object>> tools, LlmConfig config,
                                         java.util.function.Consumer<StreamChunk> onChunk) {
            onChunk.accept(new StreamChunk("tool_call", "", "read_file", "{\"file_path\":\"README.md\"}",
                    "", true, null, "call-read", 0, null));
            onChunk.accept(new StreamChunk("tool_call", "", "list_files", "{\"path\":\"src\"}",
                    "", true, null, "call-list", 1, null));
            onChunk.accept(new StreamChunk("done", "", null, null, null, true, null));
        }
    }
}
