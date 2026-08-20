package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunStateMachineTest {

    @Test
    void loopGuardStopUsesRecoverableWaitingStateInsteadOfAFalseTerminalFailure() {
        assertTrue(AgentRunStateMachine.canTransition(AgentRunState.RUNNING, AgentRunState.WAITING_RECOVERY));
        assertTrue(AgentRunStateMachine.canTransition(AgentRunState.WAITING_RECOVERY, AgentRunState.QUEUED));
        assertTrue(AgentRunStateMachine.canTransition(AgentRunState.WAITING_RECOVERY, AgentRunState.CANCELLING));
        assertFalse(AgentRunStateMachine.canTransition(AgentRunState.COMPLETED, AgentRunState.WAITING_RECOVERY));
    }
}
