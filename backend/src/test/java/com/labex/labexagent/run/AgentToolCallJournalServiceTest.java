package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunPart;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentToolCallJournalServiceTest {

    @Test
    void toolPartIsTheOnlyJournalAuthority() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/run/AgentToolCallJournalService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("AgentRunArtifactService"));
        assertFalse(source.contains("\"tool_call_state\""));
        assertFalse(source.contains("recordDeterministic"));
        assertFalse(source.contains("latestForTask"));
        assertFalse(source.contains("artifactService"));
    }

    @Test
    void requiresTheDurablePartDependencyAtConstructionTime() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);

        assertThrows(NullPointerException.class,
                () -> new AgentToolCallJournalService(lifecycle, null));
    }

    @Test
    void partPersistenceFailureStopsEventPublication() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(7L), eq("call-fail"), eq("running"), eq("run_tests"), any(), eq(2), eq("")))
                .thenThrow(new IllegalStateException("part store unavailable"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        assertThrows(IllegalStateException.class,
                () -> journal.running(7L, "call-fail", "run_tests", Map.of("command", "mvn test"), 2));

        verify(lifecycle, never()).appendEvent(any(), any(), any(), any());
    }

    @Test
    void persistsTheToolPartAndPublishesAnIdempotentTaskEvent() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(7L), eq("call-1"), eq("running"), eq("run_tests"), any(), eq(2), eq("")))
                .thenReturn(part(191L, "tool:call-1", "call-1", "run_tests"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.running(7L, "call-1", "run_tests", Map.of("command", "mvn test"), 2);

        verify(parts).upsertToolCall(eq(7L), eq("call-1"), eq("running"), eq("run_tests"), any(), eq(2), eq(""));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), payload.capture(),
                eq("tool-call-state-part-191-running"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        assertThat(event).containsEntry("partId", 191L)
                .containsEntry("partKey", "tool:call-1")
                .doesNotContainKey("artifactId");
    }

    @Test
    void completesAnExistingToolCallDirectlyFromTheDurablePart() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentRunPart existing = part(191L, "tool:call-1", "call-1", "run_tests");
        existing.setInputJson("{\"command\":\"mvn test\"}");
        existing.setStatus("completed");
        existing.setSequenceNumber(2L);
        when(parts.resolveExistingToolCall(7L, "call-1", "completed", "exit=0"))
                .thenReturn(existing);
        when(parts.projectToolCall(existing)).thenReturn(Map.of(
                "partId", 191L,
                "partKey", "tool:call-1",
                "toolCallId", "call-1",
                "tool", "run_tests",
                "arguments", Map.of("command", "mvn test"),
                "status", "completed",
                "iteration", 2L,
                "detail", "exit=0"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.completedExisting(7L, "call-1", "exit=0");

        verify(parts).resolveExistingToolCall(7L, "call-1", "completed", "exit=0");
        verify(parts, never()).upsertToolCall(any(), any(), any(), any(), any(), any(Integer.class), any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), payload.capture(),
                eq("tool-call-state-part-191-completed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) payload.getValue();
        assertThat(event.get("arguments")).isEqualTo(Map.of("command", "mvn test"));
    }

    @Test
    void waitingInteractionsRemainRecoverableToolPartStates() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(7L), eq("call-question"), eq("waiting_user"), eq("question"), any(), eq(1), eq("Waiting for an answer")))
                .thenReturn(part(193L, "tool:call-question", "call-question", "question"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);

        journal.waitingUser(7L, "call-question", "question", Map.of("question", "Continue?"), 1,
                "request-question", "Waiting for an answer", Map.of("question", "Continue?"));

        verify(parts).upsertToolCall(eq(7L), eq("call-question"), eq("waiting_user"), eq("question"),
                any(), eq(1), eq("Waiting for an answer"));
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(),
                eq("tool-call-state-part-193-waiting_user"));
    }

    private AgentRunPart part(long id, String partKey, String toolCallId, String toolName) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(id);
        part.setPartKey(partKey);
        part.setToolCallId(toolCallId);
        part.setToolName(toolName);
        return part;
    }
}
