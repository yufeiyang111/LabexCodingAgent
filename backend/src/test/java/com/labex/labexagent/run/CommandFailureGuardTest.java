package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

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
}
