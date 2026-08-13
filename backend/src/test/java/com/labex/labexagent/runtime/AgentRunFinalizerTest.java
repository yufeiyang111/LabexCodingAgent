package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.RunCompletionEvidence;
import com.labex.labexagent.run.RunCompletionEvidenceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AgentRunFinalizerTest {
    @Test
    void modelFinalCannotCompleteEvidenceUnsatisfiedTask() {
        RunCompletionEvidenceService evidenceService = Mockito.mock(RunCompletionEvidenceService.class);
        RunCompletionEvidence evidence = new RunCompletionEvidence(9L, List.of("src/App.vue"), List.of(), List.of(),
                List.of(), List.of("missing verification"), List.of(), false, LocalDateTime.now());
        when(evidenceService.evaluateAndPersist(9L, 7, 3, false, "running")).thenReturn(evidence);

        AgentRunFinalizer.CompletionAssessment result = new AgentRunFinalizer(evidenceService)
                .assess(9L, 7, 3, false);

        assertFalse(result.allowed());
        assertTrue(result.guidance().contains("verification"));
    }

    @Test
    void environmentBlockedVerificationGetsActionableEnvironmentGuidance() {
        RunCompletionEvidenceService evidenceService = Mockito.mock(RunCompletionEvidenceService.class);
        RunCompletionEvidence evidence = new RunCompletionEvidence(9L, List.of("src/App.vue"), List.of(), List.of(),
                List.of("mvn test (status=timed_out)"), List.of(), List.of(), false, LocalDateTime.now());
        when(evidenceService.evaluateAndPersist(9L, 7, 3, false, "running")).thenReturn(evidence);

        AgentRunFinalizer.CompletionAssessment result = new AgentRunFinalizer(evidenceService)
                .assess(9L, 7, 3, false);

        assertFalse(result.allowed());
        assertTrue(result.guidance().contains("environment"));
        assertTrue(result.guidance().contains("verification strategy"));
    }

    @Test
    void fencedAssessForwardsTheActiveFenceToTheCompletionEvidenceWriter() {
        ExecutionFence fence = new ExecutionFence(9L, "instance-a", 4L);
        RunCompletionEvidenceService evidenceService = Mockito.mock(RunCompletionEvidenceService.class);
        RunCompletionEvidence evidence = new RunCompletionEvidence(9L, List.of("src/App.vue"), List.of(), List.of(),
                List.of(), List.of(), List.of(), true, LocalDateTime.now());
        when(evidenceService.evaluateAndPersist(any(ExecutionFence.class), eq(9L), eq(7), eq(3), eq(false),
                eq("running"))).thenReturn(evidence);

        AgentRunFinalizer.CompletionAssessment result = new AgentRunFinalizer(evidenceService)
                .assess(fence, 9L, 7, 3, false);

        assertTrue(result.allowed());
        ArgumentCaptor<ExecutionFence> captured = ArgumentCaptor.forClass(ExecutionFence.class);
        verify(evidenceService).evaluateAndPersist(captured.capture(), eq(9L), eq(7), eq(3), eq(false),
                eq("running"));
        assertEquals(fence, captured.getValue());
    }

    @Test
    void fencedAssessPropagatesStaleFenceFailureInsteadOfCompleting() {
        ExecutionFence fence = new ExecutionFence(9L, "instance-a", 4L);
        RunCompletionEvidenceService evidenceService = Mockito.mock(RunCompletionEvidenceService.class);
        when(evidenceService.evaluateAndPersist(any(ExecutionFence.class), eq(9L), eq(7), eq(3), eq(false),
                eq("running")))
                .thenThrow(new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                        AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.EXPIRED_LEASE));

        assertThatThrownBy(() -> new AgentRunFinalizer(evidenceService).assess(fence, 9L, 7, 3, false))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .hasMessageContaining("expired-lease");
    }
}
