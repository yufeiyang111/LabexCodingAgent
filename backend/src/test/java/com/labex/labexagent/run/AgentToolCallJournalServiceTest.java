package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunArtifact;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolCallJournalServiceTest {

    @Test
    void persistsAStateArtifactAndPublishesAnIdempotentTaskEvent() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setArtifactId(91L);
        when(artifacts.recordDeterministic(eq(7L), eq("tool_call_state"), eq("call-1"), any()))
                .thenReturn(artifact);

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle);
        journal.running(7L, "call-1", "run_tests", Map.of("command", "mvn test"), 2);

        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(), eq("tool-call-state-91"));
    }

    @Test
    void latestForTaskReturnsTheLatestStateForEachToolCall() {
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunArtifact first = artifact(1L, "call-1", "running");
        AgentRunArtifact second = artifact(2L, "call-1", "completed");
        AgentRunArtifact third = artifact(3L, "call-2", "waiting_approval");
        when(artifacts.list(7L, "tool_call_state")).thenReturn(List.of(first, second, third));

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle);

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

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, parts);
        journal.waitingUser(7L, "call-question", "question", Map.of("question", "Continue?"), 1,
                "request-question", "Waiting for an answer", Map.of("question", "Continue?"));

        verify(parts).upsertToolCall(eq(7L), eq("call-question"), eq("waiting_user"), eq("question"),
                any(), eq(1), eq("Waiting for an answer"));
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(), eq("tool-call-state-93"));
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

        AgentToolCallJournalService journal = new AgentToolCallJournalService(artifacts, lifecycle, parts);
        journal.skipped(7L, "call-2", "list_files", Map.of("path", "src"), 2,
                "Skipped because an earlier call paused the run");

        verify(parts).upsertToolCall(eq(7L), eq("call-2"), eq("skipped"), eq("list_files"),
                any(), eq(2), eq("Skipped because an earlier call paused the run"));
        verify(lifecycle).appendEvent(eq(7L), eq("TOOL_CALL_STATE"), any(), eq("tool-call-state-92"));
    }

    private AgentRunArtifact artifact(long id, String path, String status) {
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setArtifactId(id);
        artifact.setArtifactPath(path);
        artifact.setContent("{\"toolCallId\":\"" + path + "\",\"status\":\"" + status + "\"}");
        return artifact;
    }
}
