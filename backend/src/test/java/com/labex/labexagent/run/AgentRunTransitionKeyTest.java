package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class AgentRunTransitionKeyTest {
    @Test
    void scopesPauseTransitionsToTheDurableInteractionOccurrence() {
        String first = AgentRunTransitionKey.forPause(
                71L, "command", "approval-1", "waiting_approval", "Awaiting approval", "Stored command");
        String replay = AgentRunTransitionKey.forPause(
                71L, "command", "approval-1", "waiting_approval", "Awaiting approval", "Stored command");
        String next = AgentRunTransitionKey.forPause(
                71L, "command", "approval-2", "waiting_approval", "Awaiting approval", "Stored command");

        assertEquals(first, replay);
        assertNotEquals(first, next);
    }
}
