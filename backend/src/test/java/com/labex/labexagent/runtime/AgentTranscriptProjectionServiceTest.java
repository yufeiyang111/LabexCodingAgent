package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTranscriptProjectionServiceTest {

    @Test
    void doesNotExposeTheRetiredMemoryFallbackProjectionApi() {
        List<String> methodNames = Arrays.stream(AgentTranscriptProjectionService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();

        assertThat(methodNames).doesNotContain("projectForProvider", "project");
    }

    @Test
    void providerLoaderReadsDurableProjectionWithoutAcceptingMemoryInput() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "durable"));
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        assertThat(service(transcript).loadProviderMessages(7L))
                .isEqualTo(messages);
    }

    @Test
    void hydratesDurableAttachmentReferencesOnlyAtProviderProjectionBoundary() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentInputAttachmentService attachments = mock(AgentInputAttachmentService.class);
        List<Map<String, Object>> durable = List.of(Map.of(
                "role", "user", "content", "inspect this", "attachmentIds", List.of("image-1")));
        List<Map<String, Object>> hydrated = List.of(Map.of(
                "role", "user", "content", List.of(Map.of("type", "text", "text", "inspect this"))));
        when(transcript.loadProjectableTranscript(7L)).thenReturn(durable);
        when(attachments.hydrateProviderMessage(7L, durable.get(0))).thenReturn(hydrated.get(0));

        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), mock(AgentCompactionService.class), attachments);

        assertThat(service.loadProviderMessages(7L)).isEqualTo(hydrated);
        org.mockito.Mockito.verify(attachments).hydrateProviderMessage(7L, durable.get(0));
    }

    @Test
    void providerLoaderFailsClosedWhenDurableProjectionIsEmpty() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        when(transcript.loadProjectableTranscript(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service(transcript).loadProviderMessages(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Durable Provider transcript is empty");
    }

    @Test
    void rejectsMissingDurableTaskIdentity() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);

        assertThatThrownBy(() -> service(transcript).loadDurableProjection(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive taskId");
    }

    @Test
    void appliesLatestCompletedCompactionForRestartRestore() {
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

        AgentTranscriptProjectionService.Projection restored = service.loadDurableProjection(7L);

        assertThat(restored.messages()).isEqualTo(compacted);
        assertThat(restored.detail()).contains("compaction_epoch=2");
    }

    private AgentTranscriptProjectionService service(AgentRunTranscriptService transcript) {
        return new AgentTranscriptProjectionService(transcript, new AgentProviderMessageProjector(),
                mock(AgentCompactionService.class));
    }
}
