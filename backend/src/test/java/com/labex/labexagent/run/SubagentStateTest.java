package com.labex.labexagent.run;
import static org.junit.jupiter.api.Assertions.assertFalse;import static org.junit.jupiter.api.Assertions.assertTrue;import org.junit.jupiter.api.Test;
class SubagentStateTest { @Test void permitsOnlyLegalLifecycleTransitions(){assertTrue(SubagentState.QUEUED.mayTransitionTo(SubagentState.RUNNING));assertTrue(SubagentState.RUNNING.mayTransitionTo(SubagentState.WAITING_USER));assertFalse(SubagentState.COMPLETED.mayTransitionTo(SubagentState.RUNNING));assertFalse(SubagentState.QUEUED.mayTransitionTo(SubagentState.COMPLETED));}}
