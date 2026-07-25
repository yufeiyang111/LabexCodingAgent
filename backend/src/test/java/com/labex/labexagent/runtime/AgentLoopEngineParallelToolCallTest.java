package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.labex.entity.AgentConversation;
import com.labex.labexagent.llm.LlmProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentLoopEngineParallelToolCallTest {

    @Test
    void serializesProviderParallelToolCallsInsteadOfTurningThemIntoModelErrors() throws Exception {
        Method chatStreaming = AgentLoopEngine.class.getDeclaredMethod(
                "chatStreaming", AgentSsePublisher.class, AgentConversation.class, String.class,
                List.class, List.class, LlmProvider.class, LlmProvider.LlmConfig.class,
                int.class, Long.class, String.class, CancellationToken.class);
        chatStreaming.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) chatStreaming.invoke(
                newEngine(), new AgentSsePublisher(new SseEmitter()), null, "system", List.of(), List.of(),
                new ParallelCallProvider(), new LlmProvider.LlmConfig("key", "https://example.test", "model", 32, 0.1),
                1, 71L, "en", CancellationToken.none());

        assertEquals("tool_call", result.get("type"));
        assertEquals("read_file", result.get("tool"));
        assertEquals("call-read", result.get("toolCallId"));
    }

    private AgentLoopEngine newEngine() throws Exception {
        Constructor<?> constructor = AgentLoopEngine.class.getConstructors()[0];
        return (AgentLoopEngine) constructor.newInstance(new Object[constructor.getParameterCount()]);
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
        }
    }
}
