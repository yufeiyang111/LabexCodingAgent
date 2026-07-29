package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.labex.labexagent.run.RunCompletionEvidence;
import com.labex.labexagent.run.RunCompletionEvidenceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentRunFinalizerTest {
    @Test
    void modelFinalCannotCompleteEvidenceUnsatisfiedTask() {
        RunCompletionEvidenceService evidenceService = Mockito.mock(RunCompletionEvidenceService.class);
        RunCompletionEvidence evidence = new RunCompletionEvidence(9L, List.of("src/App.vue"), List.of(), List.of(),
                List.of("missing verification"), List.of(), false, LocalDateTime.now());
        when(evidenceService.evaluateAndPersist(9L, 7, 3, false, "running")).thenReturn(evidence);

        AgentRunFinalizer.CompletionAssessment result = new AgentRunFinalizer(evidenceService)
                .assess(9L, 7, 3, false);

        assertFalse(result.allowed());
        assertTrue(result.guidance().contains("verification"));
    }
}
