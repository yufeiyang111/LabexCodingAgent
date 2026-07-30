package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
