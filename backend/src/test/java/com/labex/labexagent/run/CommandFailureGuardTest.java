package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.mapper.AgentRunArtifactMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandFailureGuardTest {

    @Test
    void blocksTheSameEnvironmentFailureAfterTheFirstAttempt() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);
        String command = "mvn test";

        assertThat(guard.before(71L, command, ".").allowed()).isTrue();
        guard.record(71L, "run_tests", command, ".",
                "exit=1\nNon-resolvable parent POM: Unknown host repo.maven.apache.org");

        CommandFailureGuard.Decision decision = guard.before(71L, command, ".");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("ENVIRONMENT_BLOCKED");
        assertThat(decision.message()).contains("DNS");
    }

    @Test
    void allowsOneRepairRetryForARegularCommandFailureThenBlocksIt() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);
        String command = "mvn test";

        guard.record(71L, "run_tests", command, ".", "exit=1\nThere are test failures");
        assertThat(guard.before(71L, command, ".").allowed()).isTrue();

        guard.record(71L, "run_tests", command, ".", "exit=1\nThere are test failures");
        assertThat(guard.before(71L, command, ".").allowed()).isFalse();
    }


    @Test
    void allowsTheSameVerificationAgainAfterASuccessfulWorkspaceRepair() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);
        String command = "npm run build";
        guard.record(71L, "run_tests", command, "frontend", "exit=1\nTypeScript compile failed");
        guard.record(71L, "run_tests", command, "frontend", "exit=1\nTypeScript compile failed");
        assertThat(guard.before(71L, command, "frontend").allowed()).isFalse();

        guard.recordWorkspaceChange(71L);

        assertThat(guard.before(71L, command, "frontend").allowed()).isTrue();
    }

    @Test
    void keepsEnvironmentBlockersAfterAWorkspaceRepair() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);
        String command = "mvn test";
        guard.record(71L, "run_tests", command, ".",
                "exit=1\nUnknown host repo.maven.apache.org");

        guard.recordWorkspaceChange(71L);

        CommandFailureGuard.Decision decision = guard.before(71L, command, ".");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("ENVIRONMENT_BLOCKED");
    }

    @Test
    void blocksAChangedMavenCommandAfterTheSameMavenEnvironmentFailure() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);
        guard.record(71L, "run_tests", "mvn test", ".",
                "exit=1\nNon-resolvable parent POM: Unknown host repo.maven.apache.org");

        CommandFailureGuard.Decision decision = guard.before(71L, "mvn compile", ".");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("ENVIRONMENT_BLOCKED");
    }

    @Test
    void doesNotBlockAnUnrelatedCommandScopeAfterMavenEnvironmentFailure() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);
        guard.record(71L, "run_tests", "mvn test", ".",
                "exit=1\nUnknown host repo.maven.apache.org");

        assertThat(guard.before(71L, "git status", ".").allowed()).isTrue();
    }

    @Test
    void allowsAExplicitEnvironmentRecoveryToStartANewAttemptGeneration() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);
        guard.record(71L, "run_tests", "mvn test", ".",
                "exit=1\nUnknown host repo.maven.apache.org");

        guard.reset(71L);

        assertThat(guard.before(71L, "mvn test", ".").allowed()).isTrue();
    }
    @Test
    void keepsDifferentCommandsIndependent() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 1);

        guard.record(71L, "run_tests", "mvn test", ".", "exit=1\nThere are test failures");

        assertThat(guard.before(71L, "mvn compile", ".").allowed()).isTrue();
    }

    @Test
    void blocksTheSamePolicyRejectionAfterTwoAttemptsAcrossDifferentCommandTexts() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 2);

        guard.recordPolicyBlocked(71L, "shell", "shell_operator");
        assertThat(guard.beforePolicyBlocked(71L, "shell", "shell_operator").allowed()).isTrue();

        guard.recordPolicyBlocked(71L, "shell", "shell_operator");
        CommandFailureGuard.Decision decision = guard.beforePolicyBlocked(71L, "shell", "shell_operator");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.code()).isEqualTo("REPEATED_POLICY_BLOCK");
        assertThat(decision.attempts()).isEqualTo(2);
        assertThat(decision.message()).contains("策略");
    }

    @Test
    void policyRejectionCountsAreSeparatePerToolAndReason() {
        CommandFailureGuard guard = new CommandFailureGuard(1, 1);
        guard.recordPolicyBlocked(71L, "shell", "shell_operator");

        assertThat(guard.beforePolicyBlocked(71L, "shell", "shell_operator").allowed()).isFalse();
        assertThat(guard.beforePolicyBlocked(71L, "bash", "shell_operator").allowed()).isTrue();
        assertThat(guard.beforePolicyBlocked(71L, "shell", "network_blocked").allowed()).isTrue();
        assertThat(guard.before(71L, "mvn test", ".").allowed()).isTrue();
    }

    @Test
    void activeFencePersistsCommandFailureArtifactsAndMemoryCounts() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunArtifactMapper artifacts = mock(AgentRunArtifactMapper.class);
        when(artifacts.insert(any())).thenReturn(1);
        when(artifacts.selectList(any())).thenReturn(List.of());
        CommandFailureGuard guard = new CommandFailureGuard(
                new AgentRunArtifactService(artifacts, leases), new AgentRecoveryProperties(), leases);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        guard.record(fence, 71L, "run_tests", "mvn test", ".",
                "exit=1\nNon-resolvable parent POM: Unknown host repo.maven.apache.org");

        verify(artifacts).insert(any());
        assertThat(guard.before(71L, "mvn test", ".").allowed()).isFalse();
        assertThat(guard.before(71L, "mvn test", ".").code()).isEqualTo("ENVIRONMENT_BLOCKED");
    }

    @Test
    void staleFenceRejectsCommandFailureWriteWithZeroStateChange() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunArtifactMapper artifacts = mock(AgentRunArtifactMapper.class);
        when(artifacts.insert(any())).thenReturn(1);
        when(artifacts.selectList(any())).thenReturn(List.of());
        CommandFailureGuard guard = new CommandFailureGuard(
                new AgentRunArtifactService(artifacts, leases), new AgentRecoveryProperties(), leases);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        assertThatThrownBy(() -> guard.record(fence, 71L, "run_tests", "mvn test", ".",
                "exit=1\nUnknown host repo.maven.apache.org"))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);

        verify(artifacts, never()).insert(any());
        assertThat(guard.before(71L, "mvn test", ".").allowed()).isTrue();
    }

    @Test
    void activeFencePersistsPolicyBlockedAndWorkspaceResetArtifacts() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunArtifactMapper artifacts = mock(AgentRunArtifactMapper.class);
        when(artifacts.insert(any())).thenReturn(1);
        when(artifacts.selectList(any())).thenReturn(List.of());
        CommandFailureGuard guard = new CommandFailureGuard(
                new AgentRunArtifactService(artifacts, leases), new AgentRecoveryProperties(), leases);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

        guard.recordPolicyBlocked(fence, 71L, "shell", "shell_operator");
        assertThat(guard.beforePolicyBlocked(71L, "shell", "shell_operator").allowed()).isTrue();
        guard.recordPolicyBlocked(fence, 71L, "shell", "shell_operator");
        assertThat(guard.beforePolicyBlocked(71L, "shell", "shell_operator").allowed()).isFalse();
        guard.recordWorkspaceChange(fence, 71L);
        assertThat(guard.beforePolicyBlocked(71L, "shell", "shell_operator").allowed()).isTrue();

        verify(artifacts, org.mockito.Mockito.times(3)).insert(any());
    }
}
