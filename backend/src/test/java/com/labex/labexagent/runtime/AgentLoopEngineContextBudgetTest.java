package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentModelConfig;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.context.CompactionSelection;
import com.labex.labexagent.run.AgentRunProgressProjectionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.tool.ToolRegistry;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentLoopEngineContextBudgetTest {

    @Test
    void identifiesWhenOverflowCompactionCannotReduceTheRequest() {
        assertTrue(AgentLoopEngine.hasContextCompactionProgress(1_200, 1_199));
        assertFalse(AgentLoopEngine.hasContextCompactionProgress(1_200, 1_200));
        assertFalse(AgentLoopEngine.hasContextCompactionProgress(1_200, 1_240));
    }

    @Test
    void doesNotAppendResumeDispatchMetadataAsANewProviderUserTurn() {
        assertTrue(AgentLoopEngine.shouldAppendRequestMessageToTranscript(false, false));
        assertTrue(AgentLoopEngine.shouldAppendRequestMessageToTranscript(true, false));
        assertFalse(AgentLoopEngine.shouldAppendRequestMessageToTranscript(true, true));
        assertFalse(AgentLoopEngine.shouldAppendRequestMessageToTranscript(false, true));
    }
    @Test
    void usesTheDurableProviderProjectionForBudgetAndAdmissionInputs() throws Exception {
        AgentLoopEngine engine = newEngine();
        AgentTranscriptProjectionService projection = mock(AgentTranscriptProjectionService.class);
        List<Map<String, Object>> durableMessages = List.of(
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                        "id", "call-1", "type", "function", "function", Map.of(
                                "name", "read_file", "arguments", "{\"path\":\"README.md\"}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "name", "read_file", "content", "durable result"));
        when(projection.loadProviderMessages(71L)).thenReturn(durableMessages);

        var field = AgentLoopEngine.class.getDeclaredField("transcriptProjectionService");
        field.setAccessible(true);
        field.set(engine, projection);

        assertEquals(durableMessages, engine.providerMessagesForBudget(71L));
        assertThrows(IllegalStateException.class,
                () -> engine.providerMessagesForBudget(null));
    }

    @Test
    void appendsFreshDurableProgressOnlyAtTheProviderInvocationBoundary() throws Exception {
        AgentLoopEngine engine = newEngine();
        AgentTranscriptProjectionService transcript = mock(AgentTranscriptProjectionService.class);
        AgentRunProgressProjectionService progress = mock(AgentRunProgressProjectionService.class);
        List<Map<String, Object>> durableMessages = List.of(Map.of("role", "user", "content", "durable request"));
        when(transcript.loadProviderMessages(71L)).thenReturn(durableMessages);
        when(progress.load(71L, 3L)).thenReturn(new AgentRunProgressProjectionService.Projection(
                71L, 3L, "implement", 1, 0, true, Set.of(), Set.of("durable-progress.txt"),
                "call-write", "write_file", "completed", "written", ".labex/agent-logs/run.md",
                "running", "Thinking", "", "", 8L, 0L, "agent_run_part_event"));

        var transcriptField = AgentLoopEngine.class.getDeclaredField("transcriptProjectionService");
        transcriptField.setAccessible(true);
        transcriptField.set(engine, transcript);
        engine.setRunProgressProjectionService(progress);

        List<Map<String, Object>> invocation = engine.providerMessagesForInvocation(71L, 3L);

        assertEquals(2, invocation.size());
        assertEquals(durableMessages.get(0), invocation.get(0));
        String projection = String.valueOf(invocation.get(1).get("content"));
        assertTrue(projection.contains("<agent_runtime_projection"));
        assertTrue(projection.contains("stage: implement"));
        assertTrue(projection.contains("write_count: 1"));
        assertTrue(projection.contains("durable-progress.txt"));
        assertEquals(1, engine.providerMessagesForBudget(71L).size());
    }

    @Test
    void selectsCompactionBoundariesFromTheDurableProjectionOnly() throws Exception {
        AgentLoopEngine engine = newEngine();
        AgentTranscriptProjectionService projection = mock(AgentTranscriptProjectionService.class);
        List<Map<String, Object>> durableMessages = List.of(
                Map.of("role", "user", "content", "old request"),
                Map.of("role", "assistant", "content", "old answer"),
                Map.of("role", "user", "content", "recent request"),
                Map.of("role", "assistant", "content", "recent answer"));
        when(projection.loadProviderMessages(72L)).thenReturn(durableMessages);
        var projectionField = AgentLoopEngine.class.getDeclaredField("transcriptProjectionService");
        projectionField.setAccessible(true);
        projectionField.set(engine, projection);
        engine.setContextCompactionServices(mock(AgentCompactionService.class), new AgentRequestTokenEstimator());

        CompactionSelection selection = engine.selectDurableCompaction(72L, 1, 8_000);

        assertTrue(selection.changed());
        assertEquals("old request", selection.compactedHead().get(0).get("content"));
        assertEquals("recent request", selection.retainedTail().get(0).get("content"));
    }

    @Test
    void refusesProviderBudgetProjectionWhenDurableProjectorIsUnavailable() throws Exception {
        AgentLoopEngine engine = newEngine();
        assertThrows(IllegalStateException.class,
                () -> engine.providerMessagesForBudget(71L));
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

    @Test
    void removesTheLegacyInPlaceMessagePruner() {
        assertThrows(NoSuchMethodException.class, () -> AgentLoopEngine.class.getDeclaredMethod(
                "trimMessagesIfNeeded", List.class, String.class, java.util.OptionalInt.class));
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
