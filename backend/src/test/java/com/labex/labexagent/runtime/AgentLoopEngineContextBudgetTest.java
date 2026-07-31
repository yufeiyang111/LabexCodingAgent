package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.tool.ToolRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class AgentLoopEngineContextBudgetTest {

    @Test
    void prunesOnlyEnoughNonTailToolResultsToMeetConfiguredInputBudget() throws Exception {
        List<Map<String, Object>> messages = oversizedMessages();
        String firstOldToolResult = (String) messages.get(1).get("content");
        List<Map<String, Object>> protectedTail = List.copyOf(messages.subList(4, messages.size()));

        trim(messages, OptionalInt.of(1_100));

        assertEquals(firstOldToolResult, messages.get(1).get("content"));
        assertTrue(((String) messages.get(2).get("content")).contains("[... pruned to save context"));
        assertEquals(protectedTail, messages.subList(4, messages.size()));
    }

    @Test
    void identifiesWhenOverflowCompactionCannotReduceTheRequest() {
        assertTrue(AgentLoopEngine.hasContextCompactionProgress(1_200, 1_199));
        assertFalse(AgentLoopEngine.hasContextCompactionProgress(1_200, 1_200));
        assertFalse(AgentLoopEngine.hasContextCompactionProgress(1_200, 1_240));
    }
    @Test
    void usesTheDurableProviderProjectionForBudgetAndAdmissionInputs() throws Exception {
        AgentLoopEngine engine = newEngine();
        AgentTranscriptProjectionService projection = mock(AgentTranscriptProjectionService.class);
        List<Map<String, Object>> durableMessages = List.of(Map.of("role", "user", "content", "durable"));
        when(projection.loadProviderMessages(71L)).thenReturn(durableMessages);

        var field = AgentLoopEngine.class.getDeclaredField("transcriptProjectionService");
        field.setAccessible(true);
        field.set(engine, projection);

        assertEquals(durableMessages, engine.providerMessagesForBudget(71L));
        assertThrows(IllegalStateException.class,
                () -> engine.providerMessagesForBudget(null));
    }

    @Test
    void refusesProviderBudgetProjectionWhenDurableProjectorIsUnavailable() throws Exception {
        AgentLoopEngine engine = newEngine();
        assertThrows(IllegalStateException.class,
                () -> engine.providerMessagesForBudget(71L));
    }

    @Test
    void skipsProactivePruningWhenNoConfiguredInputBudgetExists() throws Exception {
        List<Map<String, Object>> messages = oversizedMessages();
        List<Map<String, Object>> original = List.copyOf(messages);

        trim(messages, OptionalInt.empty());

        assertEquals(original, messages);
        assertFalse(messages.stream()
                .map(message -> (String) message.get("content"))
                .anyMatch(content -> content.contains("[... pruned to save context")));
    }

    @Test
    void checksStaticAdmissionBeforeAnyContextCompactionProviderCall() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"));
        int admission = source.indexOf("ContextAdmissionDecision preCompactionAdmission");
        int management = source.indexOf("this.manageContextBeforeModel(", admission);
        assertTrue(admission > 0);
        assertTrue(management > admission);
        assertTrue(source.substring(admission, management).contains("stopForContextLimit"));
    }

    private void trim(List<Map<String, Object>> messages, OptionalInt budget) throws Exception {
        Method method = AgentLoopEngine.class.getDeclaredMethod(
                "trimMessagesIfNeeded", List.class, String.class, OptionalInt.class);
        method.setAccessible(true);
        method.invoke(newEngine(), messages, "system", budget);
    }

    private AgentLoopEngine newEngine() throws Exception {
        Constructor<?> constructor = AgentLoopEngine.class.getConstructors()[0];
        AgentLoopEngine engine = (AgentLoopEngine) constructor.newInstance(new Object[constructor.getParameterCount()]);
        engine.setRunProcessors(new AgentModelTurnExecutor(),
                new AgentToolTurnExecutor(mock(ToolRegistry.class)),
                new AgentToolCallBatchProtocol(), new AgentProviderMessageProjector(),
                new AgentToolNarrator(), new com.labex.labexagent.tool.ToolSelectionPolicy(),
                new ContextAdmissionService(), new ContextAdmissionGate(),
                new AgentInteractionPauser(mock(AgentTaskService.class)));
        return engine;
    }

    private List<Map<String, Object>> oversizedMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "initial request"));
        messages.add(toolResult("first old result"));
        messages.add(toolResult("second old result"));
        messages.add(Map.of("role", "assistant", "content", "older assistant response"));
        for (int index = 0; index < 6; index++) {
            messages.add(Map.of("role", index % 2 == 0 ? "user" : "assistant", "content", "tail".repeat(15)));
        }
        return messages;
    }

    private Map<String, Object> toolResult(String name) {
        return Map.of("role", "user", "content", "[Tool " + name + " result]\n" + "x".repeat(2_100));
    }
    @Test
    void blocksProviderWhenModelContextWindowIsMissing() throws Exception {
        AgentModelConfig config = new AgentModelConfig();
        config.setMaxTokens(1_024);
        ContextAdmissionDecision decision = newEngine().evaluateContextAdmission(
                config, "system", List.of(),
                new ContextUsageEstimator.PromptContext("", "", "", ""), List.of());

        assertEquals(ContextAdmissionDecision.Action.BLOCK_STATIC_OVERFLOW, decision.action());
        assertEquals("context_window_unconfigured", decision.reasonCode());
        assertFalse(decision.providerInvocationAllowed());
    }
}
