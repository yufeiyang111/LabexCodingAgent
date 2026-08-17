package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.RunCompletionEvidence;
import com.labex.labexagent.run.RunCompletionEvidenceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentCompletionReadinessServiceTest {

    @Test
    void persistsOneDirectiveAndDurableEventForVerifiedWorkspaceChanges() {
        RunCompletionEvidenceService evidenceService = mock(RunCompletionEvidenceService.class);
        AgentRunTranscriptService transcriptService = mock(AgentRunTranscriptService.class);
        AgentRunLifecycleService lifecycleService = mock(AgentRunLifecycleService.class);
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        RunCompletionEvidence evidence = verifiedWorkspaceEvidence();
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(31L);
        when(evidenceService.evaluateAndPersist(fence, 7L, 9, 3, true, "running"))
                .thenReturn(evidence);
        when(transcriptService.appendCompletionReadinessDirective(eq(fence), eq(7L), eq(4L), anyString(), anyString()))
                .thenReturn(true);
        when(lifecycleService.appendEvent(eq(fence), eq(7L), eq("COMPLETION_READY"), any(), anyString()))
                .thenReturn(event);

        AgentCompletionReadinessService.Signal signal = new AgentCompletionReadinessService(
                evidenceService, transcriptService, lifecycleService)
                .signalIfReady(fence, 7L, 4L, 9, 3, true);

        assertThat(signal.status()).isEqualTo(AgentCompletionReadinessService.SignalStatus.SIGNALED);
        assertThat(signal.event()).isSameAs(event);
        assertThat(signal.evidenceFingerprint()).isNotBlank();
        assertThat(signal.directive()).contains("final response").contains("concrete unmet user requirement");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(lifecycleService).appendEvent(eq(fence), eq(7L), eq("COMPLETION_READY"), payload.capture(), anyString());
        assertThat(payload.getValue())
                .containsEntry("taskId", 7L)
                .containsEntry("executionEpoch", 4L)
                .containsEntry("reasonCode", "verified_workspace_change")
                .containsEntry("satisfied", true);
    }

    @Test
    void doesNotSignalForReadOnlyOrUnverifiedEvidence() {
        RunCompletionEvidenceService evidenceService = mock(RunCompletionEvidenceService.class);
        AgentRunTranscriptService transcriptService = mock(AgentRunTranscriptService.class);
        AgentRunLifecycleService lifecycleService = mock(AgentRunLifecycleService.class);
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        RunCompletionEvidence readOnly = new RunCompletionEvidence(7L, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), true, null, LocalDateTime.now());
        when(evidenceService.evaluateAndPersist(fence, 7L, 9, 3, false, "running"))
                .thenReturn(readOnly);

        AgentCompletionReadinessService.Signal signal = new AgentCompletionReadinessService(
                evidenceService, transcriptService, lifecycleService)
                .signalIfReady(fence, 7L, 4L, 9, 3, false);

        assertThat(signal.status()).isEqualTo(AgentCompletionReadinessService.SignalStatus.NOT_READY);
        assertThat(signal.reasonCode()).isEqualTo("no_workspace_change");
        verify(transcriptService, never()).appendCompletionReadinessDirective(any(), anyLong(), anyLong(), anyString(), anyString());
        verify(lifecycleService, never()).appendEvent(any(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void doesNotEmitADuplicateEventWhenTheDirectiveWasAlreadyDurablyPersisted() {
        RunCompletionEvidenceService evidenceService = mock(RunCompletionEvidenceService.class);
        AgentRunTranscriptService transcriptService = mock(AgentRunTranscriptService.class);
        AgentRunLifecycleService lifecycleService = mock(AgentRunLifecycleService.class);
        ExecutionFence fence = new ExecutionFence(7L, "instance-a", 4L);
        when(evidenceService.evaluateAndPersist(fence, 7L, 9, 3, false, "running"))
                .thenReturn(verifiedWorkspaceEvidence());
        when(transcriptService.appendCompletionReadinessDirective(eq(fence), eq(7L), eq(4L), anyString(), anyString()))
                .thenReturn(false);

        AgentCompletionReadinessService.Signal signal = new AgentCompletionReadinessService(
                evidenceService, transcriptService, lifecycleService)
                .signalIfReady(fence, 7L, 4L, 9, 3, false);

        assertThat(signal.status()).isEqualTo(AgentCompletionReadinessService.SignalStatus.ALREADY_SIGNALED);
        verify(lifecycleService, never()).appendEvent(any(), anyLong(), anyString(), any(), anyString());
    }

    private static RunCompletionEvidence verifiedWorkspaceEvidence() {
        return new RunCompletionEvidence(7L, List.of("src/App.vue"), List.of("npm run build (exit 0)"),
                List.of(), List.of(), List.of(), List.of(), true, null, LocalDateTime.now());
    }
}
