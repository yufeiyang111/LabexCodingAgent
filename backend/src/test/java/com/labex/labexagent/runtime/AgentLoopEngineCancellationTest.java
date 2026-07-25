package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.labex.entity.AgentConversation;
import com.labex.labexagent.llm.LlmProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentLoopEngineCancellationTest {

    @Test
    void streamsModelResponsesWithTheActiveRunCancellationToken() throws Exception {
        AgentCancellationRegistry.ActiveRun run = new AgentCancellationRegistry()
                .register("session-7", 7, 12, 51L);
        CapturingProvider provider = new CapturingProvider();
        Method chatStreaming = AgentLoopEngine.class.getDeclaredMethod(
                "chatStreaming",
                AgentSsePublisher.class,
                AgentConversation.class,
                String.class,
                List.class,
                List.class,
                LlmProvider.class,
                LlmProvider.LlmConfig.class,
                int.class,
                Long.class,
                String.class,
                CancellationToken.class);
        chatStreaming.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) chatStreaming.invoke(
                newEngine(),
                new AgentSsePublisher(new SseEmitter()),
                null,
                "system",
                List.of(Map.of("role", "user", "content", "hi")),
                List.of(),
                provider,
                new LlmProvider.LlmConfig("key", "https://example.test", "model", 32, 0.1),
                1,
                51L,
                "en",
                run);

        assertSame(run, provider.cancellationToken);
        assertEquals("text", result.get("type"));
    }

    private AgentLoopEngine newEngine() throws Exception {
        Constructor<?> constructor = AgentLoopEngine.class.getConstructors()[0];
        return (AgentLoopEngine) constructor.newInstance(new Object[constructor.getParameterCount()]);
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
        }

        @Override
        public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                               List<Map<String, Object>> tools, LlmConfig config,
                               CancellationToken cancellationToken,
                               java.util.function.Consumer<StreamChunk> onChunk) {
            this.cancellationToken = cancellationToken;
            onChunk.accept(new StreamChunk("text_delta", "ok", null, null, null, false, null));
        }
    }
}
