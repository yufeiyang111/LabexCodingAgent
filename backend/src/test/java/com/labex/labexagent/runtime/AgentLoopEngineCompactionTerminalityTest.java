package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.tool.ToolRegistry;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoopEngineCompactionTerminalityTest {

    @TempDir
    Path workspace;

    @Test
    void cancellationFailsTheRunningCompactionAndNeverFallsBack() throws Exception {
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentCompactionService compactionService = mock(AgentCompactionService.class);
        AgentLoopEngine engine = configuredEngine(compactionAgent, compactionService);
        AgentCompactionRecord record = runningRecord();
        when(compactionService.start(any())).thenReturn(record);
        when(compactionAgent.compact(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompactionAgent.Result.failure("Compaction cancelled"));
        CancellationToken cancellationToken = () -> true;

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invokeCompaction(engine, cancellationToken, mock(AgentSsePublisher.class), 100_000));

        assertInstanceOf(InterruptedException.class, thrown.getCause());
        assertEquals("Compaction cancelled", thrown.getCause().getMessage());
        verify(compactionService).fail(record, "Compaction cancelled");
        verify(compactionService, never()).complete(eq(record), any(), any(Integer.class));
    }

    @Test
    void unexpectedFailureImmediatelyFailsTheRunningCompaction() throws Exception {
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentCompactionService compactionService = mock(AgentCompactionService.class);
        AgentLoopEngine engine = configuredEngine(compactionAgent, compactionService);
        AgentCompactionRecord record = runningRecord();
        when(compactionService.start(any())).thenReturn(record);
        when(compactionAgent.compact(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider exploded"));

        AgentSsePublisher sse = mock(AgentSsePublisher.class);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invokeCompaction(engine, () -> false, sse, 100_000));

        IllegalStateException failure = assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals("provider exploded", failure.getMessage());
        verify(compactionService).fail(eq(record), eq("Compaction execution failed: IllegalStateException: provider exploded"));
        verify(sse).send(eq("COMPACTION_FAILED"), any());
        verify(compactionService, never()).complete(eq(record), any(), any(Integer.class));
    }

    @Test
    void finalizationFailureIsSuppressedWithoutMaskingTheOriginalError() throws Exception {
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentCompactionService compactionService = mock(AgentCompactionService.class);
        AgentLoopEngine engine = configuredEngine(compactionAgent, compactionService);
        AgentCompactionRecord record = runningRecord();
        when(compactionService.start(any())).thenReturn(record);
        when(compactionAgent.compact(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider exploded"));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("database unavailable"))
                .when(compactionService).fail(eq(record), any());

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invokeCompaction(engine, () -> false, mock(AgentSsePublisher.class), 100_000));

        IllegalStateException failure = assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals("provider exploded", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        IllegalArgumentException suppressed = assertInstanceOf(
                IllegalArgumentException.class, failure.getSuppressed()[0]);
        assertEquals("database unavailable", suppressed.getMessage());
    }

    @Test
    void projectionFailureAfterCompletedCasDoesNotRegressTheCompaction() throws Exception {
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentCompactionService compactionService = mock(AgentCompactionService.class);
        AgentLoopEngine engine = configuredEngine(compactionAgent, compactionService);
        AgentCompactionRecord record = runningRecord();
        when(compactionService.start(any())).thenReturn(record);
        when(compactionAgent.compact(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompactionAgent.Result.success("compact checkpoint", 9, "acceptance", false));
        doAnswer(invocation -> {
            record.setStatus("completed");
            return null;
        }).when(compactionService).complete(eq(record), any(), any(Integer.class));
        AgentSsePublisher sse = mock(AgentSsePublisher.class);
        doAnswer(invocation -> {
            if ("COMPACTION_SUMMARY".equals(invocation.getArgument(0))) {
                throw new IOException("client disconnected");
            }
            return null;
        }).when(sse).send(any(), any());

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invokeCompaction(engine, () -> false, sse, 100_000));

        assertInstanceOf(IOException.class, thrown.getCause());
        verify(compactionService).complete(eq(record), any(), any(Integer.class));
        verify(compactionService, never()).fail(eq(record), any());
    }

    private AgentLoopEngine configuredEngine(CompactionAgent compactionAgent,
                                             AgentCompactionService compactionService) throws Exception {
        Constructor<?> constructor = AgentLoopEngine.class.getConstructors()[0];
        Object[] arguments = new Object[constructor.getParameterCount()];
        arguments[arguments.length - 1] = compactionAgent;
        AgentLoopEngine engine = (AgentLoopEngine) constructor.newInstance(arguments);
        engine.setRunProcessors(new AgentModelTurnExecutor(),
                new AgentToolTurnExecutor(mock(ToolRegistry.class)),
                new AgentToolCallBatchProtocol(), new AgentProviderMessageProjector(),
                new AgentToolNarrator(), new com.labex.labexagent.tool.ToolSelectionPolicy(),
                new ContextAdmissionService(), new ContextAdmissionGate(),
                new AgentInteractionPauser(mock(AgentTaskService.class)));
        AgentTranscriptProjectionService projection = mock(AgentTranscriptProjectionService.class);
        List<Map<String, Object>> taskMessages = List.of(
                Map.of("role", "user", "content", "old request with enough context"),
                Map.of("role", "assistant", "content", "old answer with enough context"),
                Map.of("role", "user", "content", "recent request"),
                Map.of("role", "assistant", "content", "recent answer"));
        when(projection.loadProviderMessages(71L)).thenReturn(taskMessages);
        when(projection.loadDurableProjection(71L)).thenReturn(
                new AgentTranscriptProjectionService.Projection(taskMessages, "durable_transcript"));
        engine.setTranscriptProjectionService(projection);
        AgentRunTranscriptService transcriptService = mock(AgentRunTranscriptService.class);
        when(transcriptService.nextSequence(71L)).thenReturn(5L);
        engine.setTranscriptService(transcriptService);
        engine.setContextCompactionServices(compactionService, new AgentRequestTokenEstimator());
        when(compactionService.previousSummary(71L)).thenReturn("");
        return engine;
    }

    private Object invokeCompaction(AgentLoopEngine engine, CancellationToken cancellationToken,
                                    AgentSsePublisher sse, int tokensBefore) throws Exception {
        Method method = AgentLoopEngine.class.getDeclaredMethod("compactContextWithFallback",
                String.class, List.class, String.class, AgentContext.class, AgentSsePublisher.class,
                com.labex.entity.AgentConversation.class, AgentModelConfig.class, Integer.class,
                CancellationToken.class, int.class, int.class, int.class, String.class, long.class);
        method.setAccessible(true);
        AgentModelConfig config = new AgentModelConfig();
        config.setContextWindowTokens(128_000);
        config.setMaxTokens(2_000);
        return method.invoke(engine, "system", List.of(), "finish the task", context(), sse,
                null, config, 1, cancellationToken, 1, 8_000, tokensBefore, "proactive", 3L);
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(5);
        project.setStudentId(1);
        project.setWorkspacePath(workspace.toString());
        return AgentContext.create("session-1", 1, project, "conversation-1", 71L);
    }

    private AgentCompactionRecord runningRecord() {
        AgentCompactionRecord record = new AgentCompactionRecord();
        record.setCompactionId(81L);
        record.setTaskId(71L);
        record.setExecutionEpoch(3L);
        record.setCompactionEpoch(2L);
        record.setStatus("running");
        return record;
    }
}
