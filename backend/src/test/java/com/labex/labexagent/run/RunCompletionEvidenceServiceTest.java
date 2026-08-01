package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.labex.entity.AgentFileChange;
import com.labex.entity.AgentRunArtifact;
import com.labex.entity.AgentVerification;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentVerificationMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RunCompletionEvidenceServiceTest {
    @Test
    void collectsOwnedChangesAndVerificationAndPersistsDeterministicEvidence() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentFileChange change = new AgentFileChange();
        change.setRelativePath("src/App.vue"); change.setStatus("pending");
        AgentVerification verification = new AgentVerification();
        verification.setCommand("npm run build"); verification.setStatus("passed");
        when(changes.selectList(any())).thenReturn(List.of(change));
        when(verifications.selectList(any())).thenReturn(List.of(verification));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertTrue(evidence.satisfied());
        assertTrue(evidence.changedFiles().contains("src/App.vue"));
        verify(artifacts).recordDeterministic(eq(9L), eq("completion_evidence"), eq("task-9"), any());
    }

    @Test
    void failedVerificationKeepsEvidenceUnsatisfied() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentVerification verification = new AgentVerification();
        verification.setCommand("mvn test"); verification.setStatus("failed");
        when(changes.selectList(any())).thenReturn(List.of());
        when(verifications.selectList(any())).thenReturn(List.of(verification));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertFalse(evidence.satisfied());
    }    @Test
    void successfulVerificationClearsUnavailableOptionalPostEditDiagnostic() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentFileChange change = new AgentFileChange();
        change.setRelativePath("package.json");
        change.setStatus("pending");
        AgentVerification verification = new AgentVerification();
        verification.setCommand("npm test");
        verification.setStatus("passed");
        AgentRunArtifact unavailable = new AgentRunArtifact();
        unavailable.setContent("- status=UNAVAILABLE\n");
        when(changes.selectList(any())).thenReturn(List.of(change));
        when(verifications.selectList(any())).thenReturn(List.of(verification));
        when(artifacts.list(9L, "post_edit_verification")).thenReturn(List.of(unavailable));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertTrue(evidence.satisfied());
        assertTrue(evidence.unresolvedRisks().isEmpty());
    }

    @Test
    void laterSuccessForTheSameCommandSupersedesItsHistoricalFailure() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentVerification failed = new AgentVerification();
        failed.setVerificationId(10L);
        failed.setCommand("npm test");
        failed.setStatus("failed");
        failed.setExitCode(1);
        AgentVerification passed = new AgentVerification();
        passed.setVerificationId(11L);
        passed.setCommand("npm\t  test");
        passed.setStatus("passed");
        passed.setExitCode(0);
        when(changes.selectList(any())).thenReturn(List.of());
        when(verifications.selectList(any())).thenReturn(List.of(failed, passed));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertTrue(evidence.satisfied());
        assertEquals(List.of("npm test (exit 0)"), evidence.successfulVerifications());
        assertTrue(evidence.failedVerifications().isEmpty());
    }

    @Test
    void laterSuccessfulRunTestsKeepsHistoricalFailureForAuditWithoutBlockingCompletion() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentVerification failed = new AgentVerification();
        failed.setVerificationId(10L);
        failed.setCommand("npm test");
        failed.setStatus("failed");
        failed.setExitCode(1);
        AgentVerification passed = new AgentVerification();
        passed.setVerificationId(11L);
        passed.setCommand("npm test");
        passed.setStatus("passed");
        passed.setExitCode(0);
        AgentRunArtifact historicalFailure = new AgentRunArtifact();
        historicalFailure.setArtifactPath("run_tests:acceptance-environment-first-test");
        historicalFailure.setContent("tool=run_tests\nfailure_code=DEPENDENCY_RESOLUTION_FAILED\nexit=1");
        when(changes.selectList(any())).thenReturn(List.of());
        when(verifications.selectList(any())).thenReturn(List.of(failed, passed));
        when(artifacts.list(9L, "tool_failure")).thenReturn(List.of(historicalFailure));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertTrue(evidence.satisfied());
        assertTrue(evidence.failedVerifications().isEmpty());
        assertTrue(evidence.unresolvedRisks().isEmpty());
    }

    @Test
    void readsPersistedGeneratedAtWithoutReflectingIntoJavaTime() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setContent("""
                {"taskId":9,"changedFiles":["src/App.vue"],"successfulVerifications":["npm test"],
                 "failedVerifications":[],"unresolvedRisks":[],
                 "criteria":[{"code":"verification","label":"Verification","satisfied":true,"detail":"passed"}],
                 "satisfied":true,"generatedAt":"2026-07-27T14:23:28.123"}
                """);
        when(artifacts.latest(9L, "completion_evidence")).thenReturn(artifact);

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .latest(9L);

        assertEquals(9L, evidence.taskId());
        assertEquals("2026-07-27T14:23:28.123", evidence.generatedAt().toString());
        assertEquals(List.of("src/App.vue"), evidence.changedFiles());
        assertTrue(evidence.satisfied());
    }

    @Test
    void repeatedFinalizationReusesTheSameEvidenceVersion() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentFileChange change = new AgentFileChange();
        change.setRelativePath("src/App.vue"); change.setStatus("pending");
        AgentVerification verification = new AgentVerification();
        verification.setCommand("npm run build"); verification.setStatus("passed");
        when(changes.selectList(any())).thenReturn(List.of(change));
        when(verifications.selectList(any())).thenReturn(List.of(verification));
        RunCompletionEvidence stored = new RunCompletionPolicy().evaluate(new RunCompletionPolicy.Input(
                9L, List.of("src/App.vue"), List.of("npm run build"), List.of(), false, "running"));
        var storedPayload = new java.util.LinkedHashMap<>(stored.toPayload());
        storedPayload.put("generatedAt", "2026-07-27T14:23:28.123");
        AgentRunArtifact existing = new AgentRunArtifact();
        existing.setContent(new Gson().toJson(storedPayload));
        when(artifacts.latest(9L, "completion_evidence")).thenReturn(existing);

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertEquals("2026-07-27T14:23:28.123", evidence.generatedAt().toString());
        verify(artifacts, never()).recordDeterministic(any(), any(), any(), any());
    }

}
