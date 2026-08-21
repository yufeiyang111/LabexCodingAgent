package com.labex.monitor.incident;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IncidentStateMachineTest {

    private final IncidentStateMachine machine = new IncidentStateMachine();

    @Test
    void legalChainPasses() {
        assertThat(machine.transition("OPEN", "ACKNOWLEDGED")).isNull();
        assertThat(machine.transition("ACKNOWLEDGED", "MITIGATING")).isNull();
        assertThat(machine.transition("MITIGATING", "RESOLVED")).isNull();
        assertThat(machine.transition("RESOLVED", "CLOSED")).isNull();
    }

    @Test
    void openCanResolveOrCloseDirectly() {
        assertThat(machine.transition("OPEN", "RESOLVED")).isNull();
        assertThat(machine.transition("OPEN", "CLOSED")).isNull();
    }

    @Test
    void sameStateIsIdempotent() {
        assertThat(machine.transition("OPEN", "open")).isNull();
    }

    @Test
    void closedCannotTransition() {
        assertThat(machine.transition("CLOSED", "OPEN")).contains("illegal");
    }

    @Test
    void resolvedCannotReopen() {
        assertThat(machine.transition("RESOLVED", "OPEN")).contains("illegal");
        assertThat(machine.transition("RESOLVED", "MITIGATING")).contains("illegal");
    }

    @Test
    void unknownStatesRejected() {
        assertThat(machine.transition("BOGUS", "OPEN")).contains("unknown");
        assertThat(machine.transition("OPEN", "BOGUS")).contains("unknown");
    }

    @Test
    void nullStatesRejected() {
        assertThat(machine.transition(null, "OPEN")).contains("unknown");
        assertThat(machine.transition("OPEN", null)).contains("unknown");
    }

    @Test
    void statusValidation() {
        assertThat(machine.isValid("OPEN")).isTrue();
        assertThat(machine.isValid("closed")).isTrue();
        assertThat(machine.isValid("HOLD")).isFalse();
    }
}