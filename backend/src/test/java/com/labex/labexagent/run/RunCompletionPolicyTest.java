package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunCompletionPolicyTest {
    private final RunCompletionPolicy policy = new RunCompletionPolicy();

    @Test void allowsInformationalTaskWithoutChanges() {
        assertTrue(policy.evaluate(input(List.of(), List.of(), List.of(), false, "running")).satisfied());
    }

    @Test void rejectsChangedTaskWithoutVerification() {
        assertFalse(policy.evaluate(input(List.of("src/App.vue"), List.of(), List.of(), false, "running")).satisfied());
    }

    @Test void rejectsFailedVerificationEvenWhenAnotherVerificationPassed() {
        assertFalse(policy.evaluate(input(List.of("src/App.vue"), List.of("npm build"),
                List.of("npm test"), false, "running")).satisfied());
    }

    @Test void acceptsServerRecordedManualFileVerification() {
        assertTrue(policy.evaluate(input(List.of("README.md"), List.of(), List.of(), true, "running")).satisfied());
    }

    @Test void rejectsCancelledAndEnvironmentBlockedRuns() {
        assertFalse(policy.evaluate(input(List.of(), List.of(), List.of(), false, "cancelled")).satisfied());
        assertFalse(policy.evaluate(input(List.of(), List.of(), List.of(), false, "waiting_environment")).satisfied());
    }

    @Test void rejectsUnresolvedDiagnosticsEvenWhenManualVerificationWasRecorded() {
        RunCompletionPolicy.Input input = new RunCompletionPolicy.Input(9L,
                List.of("src/App.vue"), List.of(), List.of(), true, "running",
                List.of("post-edit verification unavailable"));

        assertFalse(policy.evaluate(input).satisfied());
    }

    @Test void environmentBlockedVerificationCannotCountAsPass() {
        RunCompletionPolicy.Input input = new RunCompletionPolicy.Input(9L,
                List.of("src/App.vue"), List.of(), List.of(), List.of("mvn test (status=timed_out)"),
                false, "running", List.of());

        RunCompletionEvidence evidence = policy.evaluate(input);

        assertFalse(evidence.satisfied());
        assertTrue(evidence.unresolvedRisks().stream().anyMatch(risk -> risk.contains("环境受阻")));
        assertTrue(evidence.criteria().stream().anyMatch(criterion ->
                "environment_verifications".equals(criterion.code()) && !criterion.satisfied()));
    }

    @Test void codeFailureStaysAFailedVerificationCriterion() {
        RunCompletionPolicy.Input input = new RunCompletionPolicy.Input(9L,
                List.of("src/App.vue"), List.of(), List.of("mvn test (exit 1)"), List.of(),
                false, "running", List.of());

        RunCompletionEvidence evidence = policy.evaluate(input);

        assertFalse(evidence.satisfied());
        assertTrue(evidence.criteria().stream().anyMatch(criterion ->
                "verification_failures".equals(criterion.code()) && !criterion.satisfied()));
    }

    private RunCompletionPolicy.Input input(List<String> changed, List<String> passed, List<String> failed,
                                             boolean manualVerified, String state) {
        return new RunCompletionPolicy.Input(9L, changed, passed, failed, manualVerified, state);
    }
}
