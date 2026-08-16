package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentVerification;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.tool.ToolResult;
import com.labex.mapper.AgentVerificationMapper;
import com.labex.mapper.AgentTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentVerificationRecorderTest {

    @Test
    void recordsApprovedProcessOutcomeAsDurableRedactedVerificationEvidence() {
        AgentVerificationMapper mapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(mapper, artifacts);
        ProcessExecutionResult result = new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 25L, "BUILD SUCCESS", false);

        boolean recorded = recorder.recordProcessResult(
                71L, 7, 12, "mvn test -Dtoken=secret", "network_retry", result);

        assertThat(recorded).isTrue();
        ArgumentCaptor<AgentVerification> verification = ArgumentCaptor.forClass(AgentVerification.class);
        verify(mapper).insert(verification.capture());
        assertThat(verification.getValue().getTaskId()).isEqualTo(71L);
        assertThat(verification.getValue().getCommand()).doesNotContain("secret").contains("<redacted>");
        assertThat(verification.getValue().getStatus()).isEqualTo("passed");
        assertThat(verification.getValue().getExitCode()).isZero();
        verify(artifacts).record(eq(71L), eq("verification_log"), eq(null),
                org.mockito.ArgumentMatchers.contains("strategy=network_retry"));
    }

    @Test
    void activeFencePersistsVerificationEvidence() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentVerificationMapper mapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(mapper, artifacts);
        recorder.setExecutionLeaseService(leases);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        boolean recorded = recorder.recordToolResult(
                fence, 71L, 7, 12, "mvn test", "auto", ToolResult.ok("BUILD SUCCESS"));

        assertThat(recorded).isTrue();
        verify(mapper).insert(any(AgentVerification.class));
        verify(artifacts).record(eq(fence), eq(71L), eq("verification_log"), isNull(),
                org.mockito.ArgumentMatchers.contains("strategy=auto"));
    }

    @Test
    void staleFenceRejectsVerificationWithZeroWrites() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentVerificationMapper mapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(mapper, artifacts);
        recorder.setExecutionLeaseService(leases);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        assertThatThrownBy(() -> recorder.recordToolResult(
                fence, 71L, 7, 12, "mvn test", "auto", ToolResult.ok("BUILD SUCCESS")))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);

        verify(mapper, never()).insert(any());
        verify(artifacts, never()).record(any(ExecutionFence.class), any(), any(), any(), any());
    }

    @Test
    void classifiesOnlyActualShellVerificationCommands() {
        assertThat(AgentVerificationRecorder.shellVerificationStrategy("npm test -- --runInBand"))
                .contains("test");
        assertThat(AgentVerificationRecorder.shellVerificationStrategy("mvn -q verify"))
                .contains("build");
        assertThat(AgentVerificationRecorder.shellVerificationStrategy("pnpm lint"))
                .contains("lint");
        assertThat(AgentVerificationRecorder.shellVerificationStrategy("npm install"))
                .isEmpty();
        assertThat(AgentVerificationRecorder.shellVerificationStrategy("git clone https://example.test/repo.git"))
                .isEmpty();
    }

    @Test
    void observedNonZeroShellVerificationIsRecordedAsFailed() {
        AgentVerificationMapper mapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(mapper, artifacts);
        ToolResult observed = ToolResult.ok("execution=completed\noutcome=non_zero_exit\nexit=1");
        observed.setExecutionStatus("failed");
        observed.setExecutionExitCode(1);

        boolean recorded = recorder.recordShellToolResult(71L, 7, 12, "npm test", observed);

        assertThat(recorded).isTrue();
        ArgumentCaptor<AgentVerification> verification = ArgumentCaptor.forClass(AgentVerification.class);
        verify(mapper).insert(verification.capture());
        assertThat(verification.getValue().getStatus()).isEqualTo("failed");
        assertThat(verification.getValue().getExitCode()).isEqualTo(1);
    }

    @Test
    void onlyRealExitZeroCountsAsPassed() {
        AgentVerificationMapper mapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(mapper, artifacts);
        ProcessExecutionResult nonZero = new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 25L, "BUILD SUCCESS", false);
        assertThat(nonZero.succeeded()).isTrue();

        recorder.recordProcessResult(71L, 7, 12, "mvn test", "auto", nonZero);

        ArgumentCaptor<AgentVerification> verification = ArgumentCaptor.forClass(AgentVerification.class);
        verify(mapper).insert(verification.capture());
        assertThat(verification.getValue().getStatus()).isEqualTo("passed");
    }

    @Test
    void timedOutCancelledAndInfrastructureErrorsKeepDistinctVerificationStatus() {
        AgentVerificationMapper mapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(mapper, artifacts);

        recorder.recordProcessResult(71L, 7, 12, "mvn test", "auto",
                new ProcessExecutionResult(ExecutionStatus.TIMED_OUT, null, 300_000L, "killed", false));
        recorder.recordProcessResult(71L, 7, 12, "mvn test", "auto",
                new ProcessExecutionResult(ExecutionStatus.CANCELLED, null, 3_000L, "aborted", false));
        recorder.recordProcessResult(71L, 7, 12, "mvn test", "auto",
                new ProcessExecutionResult(ExecutionStatus.INFRASTRUCTURE_ERROR, null, 40L, "spawn failed", false));

        ArgumentCaptor<AgentVerification> verification = ArgumentCaptor.forClass(AgentVerification.class);
        verify(mapper, org.mockito.Mockito.times(3)).insert(verification.capture());
        assertThat(verification.getAllValues().get(0).getStatus()).isEqualTo("timed_out");
        assertThat(verification.getAllValues().get(1).getStatus()).isEqualTo("cancelled");
        assertThat(verification.getAllValues().get(2).getStatus()).isEqualTo("infrastructure_error");
    }

    @Test
    void failedProcessResultStaysFailedNotPassed() {
        AgentVerificationMapper mapper = mock(AgentVerificationMapper.class);
        AgentRunArtifactService artifacts = mock(AgentRunArtifactService.class);
        AgentVerificationRecorder recorder = new AgentVerificationRecorder(mapper, artifacts);

        recorder.recordProcessResult(71L, 7, 12, "mvn test", "auto",
                new ProcessExecutionResult(ExecutionStatus.FAILED, 1, 25L, "BUILD FAILURE", false));

        ArgumentCaptor<AgentVerification> verification = ArgumentCaptor.forClass(AgentVerification.class);
        verify(mapper).insert(verification.capture());
        assertThat(verification.getValue().getStatus()).isEqualTo("failed");
        assertThat(verification.getValue().getExitCode()).isEqualTo(1);
    }
}
