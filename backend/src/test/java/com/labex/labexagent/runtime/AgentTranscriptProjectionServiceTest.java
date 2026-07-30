package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTranscriptProjectionServiceTest {

    @Test
    void selectsDurableTranscriptOnlyWhenShadowProjectionMatches() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hello"));
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        AgentTranscriptProjectionService.Projection projection =
                new AgentTranscriptProjectionService(transcript).project(7L, messages);

        assertThat(projection.source()).isEqualTo(AgentTranscriptProjectionService.Source.DURABLE);
        assertThat(projection.shadowMismatch()).isFalse();
        assertThat(projection.messages()).isEqualTo(messages);
    }

    @Test
    void providerLoaderReadsDurableProjectionWithoutAcceptingMemoryInput() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "durable"));
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        assertThat(new AgentTranscriptProjectionService(transcript).loadProviderMessages(7L))
                .isEqualTo(messages);
    }

    @Test
    void providerLoaderFailsClosedWhenDurableProjectionIsEmpty() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        when(transcript.loadProjectableTranscript(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> new AgentTranscriptProjectionService(transcript).loadProviderMessages(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Durable Provider transcript is empty");
    }

    @Test
    void fallsBackToMemoryAndReportsMismatchInsteadOfSilentlyChangingPrompt() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        when(transcript.loadProjectableTranscript(7L))
                .thenReturn(List.of(Map.of("role", "user", "content", "stale")));
        List<Map<String, Object>> current = List.of(Map.of("role", "user", "content", "current"));

        AgentTranscriptProjectionService.Projection projection =
                new AgentTranscriptProjectionService(transcript).project(7L, current);

        assertThat(projection.source()).isEqualTo(AgentTranscriptProjectionService.Source.MEMORY);
        assertThat(projection.shadowMismatch()).isTrue();
        assertThat(projection.messages()).isEqualTo(current);
        assertThat(projection.detail()).contains("durable=1").contains("memory=1");
    }
    @Test
    void strictProviderProjectionRejectsDurableDivergenceInsteadOfFallingBackToMemory() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        when(transcript.loadProjectableTranscript(7L))
                .thenReturn(List.of(Map.of("role", "user", "content", "stale")));
        List<Map<String, Object>> current = List.of(Map.of("role", "user", "content", "current"));

        assertThatThrownBy(() -> new AgentTranscriptProjectionService(transcript)
                .projectForProvider(7L, current))
                .isInstanceOf(AgentTranscriptProjectionDivergenceException.class)
                .hasMessageContaining("taskId=7")
                .hasMessageContaining("durable=1")
                .hasMessageContaining("memory=1");
    }

    @Test
    void appliesLatestCompletedCompactionForShadowComparisonAndRestartRestore() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        List<Map<String, Object>> compacted = List.of(
                Map.of("role", "user", "content", "durable summary"),
                Map.of("role", "user", "content", "retained"),
                Map.of("role", "assistant", "content", "answer"),
                Map.of("role", "user", "content", "later"));
        when(compactions.projectLatest(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new AgentCompactionService.Projection(compacted, 2L, 9L)));
        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), compactions);

        AgentTranscriptProjectionService.Projection live = service.project(7L, compacted);
        AgentTranscriptProjectionService.Projection restored = service.loadDurableProjection(7L);

        assertThat(live.source()).isEqualTo(AgentTranscriptProjectionService.Source.DURABLE);
        assertThat(live.shadowMismatch()).isFalse();
        assertThat(live.detail()).contains("compaction_epoch=2");
        assertThat(restored.messages()).isEqualTo(compacted);
        assertThat(restored.source()).isEqualTo(AgentTranscriptProjectionService.Source.DURABLE);
    }
}
