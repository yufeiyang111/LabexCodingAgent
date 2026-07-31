package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.labex.labexagent.llm.LlmProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentLoopEngineCancellationTest {

    @Test
    void streamsModelResponsesWithTheActiveRunCancellationToken() throws Exception {
        AgentCancellationRegistry.ActiveRun run = new AgentCancellationRegistry()
                .register("session-7", 7, 12, 51L);
        CapturingProvider provider = new CapturingProvider();
        AgentModelTurnExecutor executor = new AgentModelTurnExecutor(5_000L);
        AgentModelTurnExecutor.ModelTurnResult result = executor.execute(new AgentModelTurnExecutor.ModelTurnRequest(
                "system", List.of(Map.of("role", "user", "content", "hi")), List.of(), provider,
                new LlmProvider.LlmConfig("key", "https://example.test", "model", 32, 0.1),
                1, 51L, "en", run, new NoopSink()));
        assertSame(run, provider.cancellationToken);
        assertEquals(AgentModelTurnExecutor.ResultType.TEXT, result.type());
    }

    private static final class NoopSink implements AgentModelTurnExecutor.EventSink {
        public void durable(String type, Object data) {}
        public void transientEvent(String type, Object data) {}
    }

    private static final class CapturingProvider implements LlmProvider {
        private CancellationToken cancellationToken;

        @Override
        public String getProviderId() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }

        @Override
        public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                                 List<Map<String, Object>> tools, LlmConfig config) {
            return Map.of();
        }

        @Override
        public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                               List<Map<String, Object>> tools, LlmConfig config,
                               java.util.function.Consumer<StreamChunk> onChunk) {
            onChunk.accept(new StreamChunk("text_delta", "ok", null, null, null, false, null));
            onChunk.accept(new StreamChunk("done", "", null, null, null, true, null));
        }

        @Override
        public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                               List<Map<String, Object>> tools, LlmConfig config,
                               CancellationToken cancellationToken,
                               java.util.function.Consumer<StreamChunk> onChunk) {
            this.cancellationToken = cancellationToken;
            onChunk.accept(new StreamChunk("text_delta", "ok", null, null, null, false, null));
            onChunk.accept(new StreamChunk("done", "", null, null, null, true, null));
        }
    }
}
