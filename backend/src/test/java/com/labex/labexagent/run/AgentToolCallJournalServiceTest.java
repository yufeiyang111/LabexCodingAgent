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

import com.labex.entity.AgentRunArtifact;
import com.labex.entity.AgentRunPart;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentToolCallJournalServiceTest {

    @Test
    void durablePartJournalMustNotBeOptionalOrSwallowPersistenceFailures() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/run/AgentToolCallJournalService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("this(artifactService, lifecycleService, null)"));
        assertFalse(source.contains("partService != null"));
        assertFalse(source.contains("catch (RuntimeException ignored) {\n            // Part"));
        assertFalse(source.contains("catch (RuntimeException ignored)"));
    }

    @Test
    void requiresTheDurablePartDependencyAtConstructionTime() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);

        assertThrows(NullPointerException.class,
                () -> new AgentToolCallJournalService(artifacts, lifecycle, null));
    }

    @Test
    void partPersistenceFailureStopsArtifactAndEventPublication() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(7L), eq("call-fail"), eq("running"), eq("run_tests"), any(), eq(2), eq("")))
                .thenThrow(new IllegalStateException("part store unavailable"));
        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, parts);

        assertThrows(IllegalStateException.class,
                () -> journal.running(7L, "call-fail", "run_tests", Map.of("command", "mvn test"), 2));

        verify(artifacts, never()).recordDeterministic(any(), any(), any(), any());
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any());
    }

    @Test
    void persistsAStateArtifactAndPublishesAnIdempotentTaskEvent() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setArtifactId(91L);
        when(artifacts.recordDeterministic(eq(7L), eq("tool_call_state"), eq("call-1"), any()))
                .thenReturn(artifact);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.upsertToolCall(eq(7L), eq("call-1"), eq("running"), eq("run_tests"), any(), eq(2), eq("")))
                .thenReturn(part(191L, "call-1"));

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, parts);
        journal.running(7L, "call-1", "run_tests", Map.of("command", "mvn test"), 2);

        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(), eq("tool-call-state-part-191-running"));
    }

    @Test
    void latestForTaskReturnsTheLatestStateForEachToolCall() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunArtifact first = artifact(1L, "call-1", "running");
        AgentRunArtifact second = artifact(2L, "call-1", "completed");
        AgentRunArtifact third = artifact(3L, "call-2", "waiting_approval");
        when(artifacts.list(7L, "tool_call_state")).thenReturn(List.of(first, second, third));

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, mock(AgentRunPartService.class));

        List<Map<String, Object>> latest = journal.latestForTask(7L);

        assertThat(latest).hasSize(2);
        assertThat(latest).extracting(state -> state.get("status"))
                .containsExactly("completed", "waiting_approval");
    }

    @Test
    void waitingUserCallIsPersistedAsARecoverableWaitingState() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setArtifactId(93L);
        when(artifacts.recordDeterministic(eq(7L), eq("tool_call_state"), eq("call-question"), any()))
                .thenReturn(artifact);
        when(parts.upsertToolCall(eq(7L), eq("call-question"), eq("waiting_user"), eq("question"), any(), eq(1), eq("Waiting for an answer")))
                .thenReturn(part(193L, "call-question"));

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, parts);
        journal.waitingUser(7L, "call-question", "question", Map.of("question", "Continue?"), 1,
                "request-question", "Waiting for an answer", Map.of("question", "Continue?"));

        verify(parts).upsertToolCall(eq(7L), eq("call-question"), eq("waiting_user"), eq("question"),
                any(), eq(1), eq("Waiting for an answer"));
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(), eq("tool-call-state-part-193-waiting_user"));
    }

    @Test
    void permissionInteractionIsPersistedAsWaitingApprovalInsteadOfAQuestion() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setArtifactId(94L);
        when(artifacts.recordDeterministic(eq(7L), eq("tool_call_state"), eq("call-permission"), any()))
                .thenReturn(artifact);
        when(parts.upsertToolCall(eq(7L), eq("call-permission"), eq("waiting_approval"), eq("read_file"), any(), eq(1), eq("Waiting for user approval.")))
                .thenReturn(part(194L, "call-permission"));

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, parts);
        journal.waitingInteraction(7L, "call-permission", "read_file", Map.of("file_path", ".env"), 1,
                "request-permission", "permission", "Waiting for user approval.",
                Map.of("requestId", "request-permission", "interactionType", "permission"));

        verify(parts).upsertToolCall(eq(7L), eq("call-permission"), eq("waiting_approval"), eq("read_file"),
                any(), eq(1), eq("Waiting for user approval."));
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(), eq("tool-call-state-part-194-waiting_approval"));
    }

    @Test
    void skippedCallIsPersistedAsAnExplicitTerminalState() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setArtifactId(92L);
        when(artifacts.recordDeterministic(eq(7L), eq("tool_call_state"), eq("call-2"), any()))
                .thenReturn(artifact);
        when(parts.upsertToolCall(eq(7L), eq("call-2"), eq("skipped"), eq("list_files"), any(), eq(2), eq("Skipped because an earlier call paused the run")))
                .thenReturn(part(192L, "call-2"));

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, parts);
        journal.skipped(7L, "call-2", "list_files", Map.of("path", "src"), 2,
                "Skipped because an earlier call paused the run");

        verify(parts).upsertToolCall(eq(7L), eq("call-2"), eq("skipped"), eq("list_files"),
                any(), eq(2), eq("Skipped because an earlier call paused the run"));
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(), eq("tool-call-state-part-192-skipped"));
    }

    private AgentRunPart part(long id, String key) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(id);
        part.setPartKey(key);
        return part;
    }

    private AgentRunArtifact artifact(long id, String path, String status) {
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setArtifactId(id);
        artifact.setArtifactPath(path);
        artifact.setContent("{\"toolCallId\":\"" + path + "\",\"status\":\"" + status + "\"}");
        return artifact;
    }
}
