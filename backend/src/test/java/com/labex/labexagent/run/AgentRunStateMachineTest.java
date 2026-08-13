package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AgentRunStateMachineTest {

    private static final Set<Transition> LEGAL_TRANSITIONS = Set.of(
            transition(AgentRunState.QUEUED, AgentRunState.PREPARING),
            transition(AgentRunState.QUEUED, AgentRunState.RECOVERING),
            transition(AgentRunState.QUEUED, AgentRunState.CANCELLING),
            transition(AgentRunState.QUEUED, AgentRunState.FAILED),
            transition(AgentRunState.QUEUED, AgentRunState.WAITING_WORKSPACE),
            transition(AgentRunState.PREPARING, AgentRunState.RUNNING),
            transition(AgentRunState.PREPARING, AgentRunState.RECOVERING),
            transition(AgentRunState.PREPARING, AgentRunState.WAITING_ENVIRONMENT),
            transition(AgentRunState.PREPARING, AgentRunState.CANCELLING),
            transition(AgentRunState.PREPARING, AgentRunState.FAILED),
            transition(AgentRunState.PREPARING, AgentRunState.WAITING_WORKSPACE),
            transition(AgentRunState.RUNNING, AgentRunState.WAITING_APPROVAL),
            transition(AgentRunState.RUNNING, AgentRunState.WAITING_USER),
            transition(AgentRunState.RUNNING, AgentRunState.WAITING_ENVIRONMENT),
            transition(AgentRunState.RUNNING, AgentRunState.RETRYING),
            transition(AgentRunState.RUNNING, AgentRunState.RECOVERING),
            transition(AgentRunState.RUNNING, AgentRunState.CANCELLING),
            transition(AgentRunState.RUNNING, AgentRunState.COMPLETED),
            transition(AgentRunState.RUNNING, AgentRunState.FAILED),
            transition(AgentRunState.WAITING_APPROVAL, AgentRunState.RUNNING),
            transition(AgentRunState.WAITING_APPROVAL, AgentRunState.RECOVERING),
            transition(AgentRunState.WAITING_APPROVAL, AgentRunState.CANCELLING),
            transition(AgentRunState.WAITING_APPROVAL, AgentRunState.FAILED),
            transition(AgentRunState.WAITING_USER, AgentRunState.RUNNING),
            transition(AgentRunState.WAITING_USER, AgentRunState.RECOVERING),
            transition(AgentRunState.WAITING_USER, AgentRunState.CANCELLING),
            transition(AgentRunState.WAITING_USER, AgentRunState.FAILED),
            transition(AgentRunState.RETRYING, AgentRunState.RUNNING),
            transition(AgentRunState.RETRYING, AgentRunState.RECOVERING),
            transition(AgentRunState.WAITING_WORKSPACE, AgentRunState.QUEUED),
            transition(AgentRunState.WAITING_WORKSPACE, AgentRunState.CANCELLING),
            transition(AgentRunState.WAITING_WORKSPACE, AgentRunState.FAILED),
            transition(AgentRunState.WAITING_ENVIRONMENT, AgentRunState.QUEUED),
            transition(AgentRunState.WAITING_ENVIRONMENT, AgentRunState.CANCELLING),
            transition(AgentRunState.WAITING_ENVIRONMENT, AgentRunState.FAILED),
            transition(AgentRunState.RECOVERING, AgentRunState.PREPARING),
            transition(AgentRunState.RECOVERING, AgentRunState.RUNNING),
            transition(AgentRunState.RECOVERING, AgentRunState.WAITING_WORKSPACE),
            transition(AgentRunState.RECOVERING, AgentRunState.CANCELLING),
            transition(AgentRunState.RECOVERING, AgentRunState.FAILED),
            transition(AgentRunState.RETRYING, AgentRunState.CANCELLING),
            transition(AgentRunState.RETRYING, AgentRunState.CANCELLED),
            transition(AgentRunState.RETRYING, AgentRunState.FAILED),
            transition(AgentRunState.CANCELLING, AgentRunState.CANCELLED)
    );

    @ParameterizedTest
    @MethodSource("legalTransitions")
    void acceptsEveryLegalTransition(AgentRunState from, AgentRunState to) {
        assertTrue(AgentRunStateMachine.canTransition(from, to));
        assertDoesNotThrow(() -> AgentRunStateMachine.requireTransition(from, to));
    }

    @ParameterizedTest
    @MethodSource("illegalTransitions")
    void rejectsEveryIllegalTransition(AgentRunState from, AgentRunState to) {
        assertFalse(AgentRunStateMachine.canTransition(from, to));
        assertThrows(IllegalStateException.class, () -> AgentRunStateMachine.requireTransition(from, to));
    }

    @Test
    void legacyPendingStatusMapsToQueued() {
        assertTrue(AgentRunState.fromPersistedStatus("pending") == AgentRunState.QUEUED);
        assertTrue(AgentRunState.fromPersistedStatus("waiting_user") == AgentRunState.WAITING_USER);
    }

    @Test
    void acceptsRecoveryThenPreparationForRestoredRuns() {
        // SECURE TARGET: 恢复后的任务必须先重建 runtime projection（PREPARING）再进入 running
        assertTrue(AgentRunStateMachine.canTransition(AgentRunState.RECOVERING, AgentRunState.PREPARING));
        assertDoesNotThrow(() -> AgentRunStateMachine.requireTransition(
                AgentRunState.RECOVERING, AgentRunState.PREPARING));
    }

    @Test
    void acceptsPreparingBackToWaitingEnvironmentOnControlledRetry() {
        // SECURE TARGET: PREPARING 阶段的可重试环境失败必须能回到 waiting_environment
        assertTrue(AgentRunStateMachine.canTransition(AgentRunState.PREPARING, AgentRunState.WAITING_ENVIRONMENT));
        assertDoesNotThrow(() -> AgentRunStateMachine.requireTransition(
                AgentRunState.PREPARING, AgentRunState.WAITING_ENVIRONMENT));
    }

    private static Stream<Arguments> legalTransitions() {
        return LEGAL_TRANSITIONS.stream().map(transition -> Arguments.of(transition.from(), transition.to()));
    }

    private static Stream<Arguments> illegalTransitions() {
        return EnumSet.allOf(AgentRunState.class).stream()
                .flatMap(from -> EnumSet.allOf(AgentRunState.class).stream()
                        .map(to -> transition(from, to)))
                .filter(transition -> !LEGAL_TRANSITIONS.contains(transition))
                .map(transition -> Arguments.of(transition.from(), transition.to()));
    }

    private static Transition transition(AgentRunState from, AgentRunState to) {
        return new Transition(from, to);
    }

    private record Transition(AgentRunState from, AgentRunState to) {
    }
}
