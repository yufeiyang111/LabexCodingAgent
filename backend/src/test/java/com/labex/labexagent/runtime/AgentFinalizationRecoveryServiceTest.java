package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.run.PreviewEvidence;
import com.labex.labexagent.run.RunCompletionEvidence;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentFinalizationRecoveryServiceTest {

    @Test
    void permitsOneRecoveryForNewDurableCompletionEvidence() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.currentEpochFinalizationRecoveryAttempts(anyLong(), anyLong(), anyString())).thenReturn(0);
        AgentLoopProperties properties = properties(1);

        AgentFinalizationRecoveryService.Decision decision = new AgentFinalizationRecoveryService(parts, properties)
                .decide(7L, 4L, evidence("http://localhost:5000/"), "请访问错误地址 http://127.0.0.1:5000/");

        assertTrue(decision.recoveryAllowed());
        assertEquals(0, decision.previousAttempts());
        assertEquals(1, decision.recoveryAttempt());
        assertEquals(1, decision.recoveryLimit());
        assertFalse(decision.evidenceFingerprint().isBlank());
        assertFalse(decision.finalClaimFingerprint().isBlank());
    }

    @Test
    void exhaustsRecoveryWhenTheSameDurableEvidenceAlreadyUsedItsBudget() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.currentEpochFinalizationRecoveryAttempts(anyLong(), anyLong(), anyString())).thenReturn(1);

        AgentFinalizationRecoveryService.Decision decision = new AgentFinalizationRecoveryService(parts, properties(1))
                .decide(7L, 4L, evidence("http://localhost:5000/"), "请访问错误地址 http://127.0.0.1:5000/");

        assertFalse(decision.recoveryAllowed());
        assertEquals(1, decision.previousAttempts());
        assertEquals(1, decision.recoveryAttempt());
    }

    @Test
    void changesTheDurableEvidenceFingerprintWhenPreviewFactChanges() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        when(parts.currentEpochFinalizationRecoveryAttempts(anyLong(), anyLong(), anyString())).thenReturn(0);
        AgentFinalizationRecoveryService service = new AgentFinalizationRecoveryService(parts, properties(1));

        AgentFinalizationRecoveryService.Decision first = service.decide(
                7L, 4L, evidence("http://localhost:5000/"), "完成");
        AgentFinalizationRecoveryService.Decision changed = service.decide(
                7L, 4L, evidence("http://localhost:5001/"), "完成");

        assertNotEquals(first.evidenceFingerprint(), changed.evidenceFingerprint());
    }

    private static AgentLoopProperties properties(int limit) {
        AgentLoopProperties properties = new AgentLoopProperties();
        properties.setFinalizationRecoveryLimit(limit);
        return properties;
    }

    private static RunCompletionEvidence evidence(String previewUrl) {
        return new RunCompletionEvidence(7L, List.of("app.py"), List.of("pytest"), List.of(), List.of(), List.of(),
                List.of(), true, new PreviewEvidence(PreviewEvidence.Status.READY, previewUrl, "", ""), LocalDateTime.now());
    }
}
