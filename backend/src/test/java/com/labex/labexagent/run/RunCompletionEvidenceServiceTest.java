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
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunExecutionLeaseService.StaleExecutionFenceException;
import com.labex.entity.AgentVerification;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentTaskMapper;
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
    void environmentBlockedVerificationIsSeparatedFromCodeFailure() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentFileChange change = new AgentFileChange();
        change.setRelativePath("src/App.vue"); change.setStatus("pending");
        AgentVerification timedOut = new AgentVerification();
        timedOut.setVerificationId(21L);
        timedOut.setCommand("mvn test"); timedOut.setStatus("timed_out"); timedOut.setExitCode(null);
        when(changes.selectList(any())).thenReturn(List.of(change));
        when(verifications.selectList(any())).thenReturn(List.of(timedOut));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertFalse(evidence.satisfied());
        assertTrue(evidence.failedVerifications().isEmpty());
        assertTrue(evidence.environmentVerifications().contains("mvn test (status=timed_out)"));
        assertTrue(evidence.unresolvedRisks().stream()
                .anyMatch(risk -> risk.contains("环境受阻") && risk.contains("mvn test")));
    }

    @Test
    void codeFailureAndEnvironmentBlockKeepDistinctLabels() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentVerification failed = new AgentVerification();
        failed.setVerificationId(31L);
        failed.setCommand("mvn test"); failed.setStatus("failed"); failed.setExitCode(1);
        AgentVerification infra = new AgentVerification();
        infra.setVerificationId(32L);
        infra.setCommand("mvn test -Pintegration"); infra.setStatus("infrastructure_error"); infra.setExitCode(null);
        when(changes.selectList(any())).thenReturn(List.of());
        when(verifications.selectList(any())).thenReturn(List.of(failed, infra));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts)
                .evaluateAndPersist(9L, 7, 3, false, "running");

        assertFalse(evidence.satisfied());
        assertTrue(evidence.failedVerifications().contains("mvn test (exit 1)"));
        assertTrue(evidence.environmentVerifications().contains("mvn test -Pintegration (status=infrastructure_error)"));
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
    void fencedCompletionEvidenceRejectsStaleOwnerBeforeWritingArtifact() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(9L)).thenReturn(fencedTask("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        RunCompletionEvidenceService service = new RunCompletionEvidenceService(changes, verifications, artifacts,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = org.junit.jupiter.api.Assertions.assertThrows(
                StaleExecutionFenceException.class,
                () -> service.evaluateAndPersist(new ExecutionFence(9L, "instance-a", 4L),
                        9L, 7, 3, false, "running"));

        org.junit.jupiter.api.Assertions.assertEquals(
                StaleExecutionFenceException.Reason.STALE_OWNER, error.reason());
        verify(artifacts, never()).recordDeterministic(any(), any(), any(), any());
        verify(artifacts, never()).recordDeterministic(any(ExecutionFence.class), any(), any(), any(), any());
    }

    @Test
    void fencedCompletionEvidenceRejectsStaleEpochBeforeWritingArtifact() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(9L)).thenReturn(fencedTask("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        RunCompletionEvidenceService service = new RunCompletionEvidenceService(changes, verifications, artifacts,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = org.junit.jupiter.api.Assertions.assertThrows(
                StaleExecutionFenceException.class,
                () -> service.evaluateAndPersist(new ExecutionFence(9L, "instance-a", 3L),
                        9L, 7, 3, false, "running"));

        org.junit.jupiter.api.Assertions.assertEquals(
                StaleExecutionFenceException.Reason.STALE_EPOCH, error.reason());
        verify(artifacts, never()).recordDeterministic(any(), any(), any(), any());
        verify(artifacts, never()).recordDeterministic(any(ExecutionFence.class), any(), any(), any(), any());
    }

    @Test
    void fencedCompletionEvidenceRejectsExpiredLeaseBeforeWritingArtifact() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(9L)).thenReturn(fencedTask("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 9, 59, 59)));

        RunCompletionEvidenceService service = new RunCompletionEvidenceService(changes, verifications, artifacts,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = org.junit.jupiter.api.Assertions.assertThrows(
                StaleExecutionFenceException.class,
                () -> service.evaluateAndPersist(new ExecutionFence(9L, "instance-a", 4L),
                        9L, 7, 3, false, "running"));

        org.junit.jupiter.api.Assertions.assertEquals(
                StaleExecutionFenceException.Reason.EXPIRED_LEASE, error.reason());
        verify(artifacts, never()).recordDeterministic(any(), any(), any(), any());
        verify(artifacts, never()).recordDeterministic(any(ExecutionFence.class), any(), any(), any(), any());
    }

    @Test
    void fencedCompletionEvidencePersistsArtifactWhenTheFenceIsActive() {
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        AgentFileChange change = new AgentFileChange();
        change.setRelativePath("src/App.vue"); change.setStatus("pending");
        AgentVerification verification = new AgentVerification();
        verification.setCommand("npm run build"); verification.setStatus("passed");
        when(tasks.selectCount(any())).thenReturn(1L);
        when(changes.selectList(any())).thenReturn(List.of(change));
        when(verifications.selectList(any())).thenReturn(List.of(verification));

        RunCompletionEvidence evidence = new RunCompletionEvidenceService(changes, verifications, artifacts,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L))
                .evaluateAndPersist(new ExecutionFence(9L, "instance-a", 4L),
                        9L, 7, 3, false, "running");

        assertTrue(evidence.satisfied());
        assertTrue(evidence.changedFiles().contains("src/App.vue"));
        // completion 写入发生在任务终态迁移之前、lease 仍由 executor 持有的窗口内。
        verify(artifacts).recordDeterministic(any(ExecutionFence.class),
                eq(9L), eq("completion_evidence"), eq("task-9"), any());
    }

    @Test
    void rejectedFencedCompletionEvidenceLeaksNoSentinelIntoArtifactOrErrorPayload() {
        String sentinel = "SENTINEL-SECRET-5d6e7f";
        AgentFileChangeMapper changes = Mockito.mock(AgentFileChangeMapper.class);
        AgentVerificationMapper verifications = Mockito.mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = Mockito.mock(AgentRunArtifactService.class);
        AgentTaskMapper tasks = Mockito.mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(9L)).thenReturn(fencedTask("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));
        AgentVerification verification = new AgentVerification();
        verification.setCommand("npm run build --token=" + sentinel); verification.setStatus("passed");
        when(changes.selectList(any())).thenReturn(List.of());
        when(verifications.selectList(any())).thenReturn(List.of(verification));

        RunCompletionEvidenceService service = new RunCompletionEvidenceService(changes, verifications, artifacts,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        StaleExecutionFenceException error = org.junit.jupiter.api.Assertions.assertThrows(
                StaleExecutionFenceException.class,
                () -> service.evaluateAndPersist(new ExecutionFence(9L, "instance-a", 4L),
                        9L, 7, 3, false, "running"));

        assertFalse(error.getMessage().contains(sentinel));
        verify(artifacts, never()).recordDeterministic(any(), any(), any(), any());
        verify(artifacts, never()).recordDeterministic(any(ExecutionFence.class), any(), any(), any(), any());
    }

    private AgentTask fencedTask(String owner, long epoch, java.time.LocalDateTime leaseExpiresAt) {
        AgentTask task = new AgentTask();
        task.setTaskId(9L);
        task.setExecutionOwner(owner);
        task.setExecutionEpoch(epoch);
        task.setExecutionLeaseExpiresAt(leaseExpiresAt);
        return task;
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
