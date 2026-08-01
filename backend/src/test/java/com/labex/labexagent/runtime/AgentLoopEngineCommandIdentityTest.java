package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AgentLoopEngineCommandIdentityTest {

    @Test
    void derivesStableRecoveredToolCallIdentityFromCanonicalInputs() {
        String first = AgentLoopEngine.recoveredToolCallIdentity(71L, 3, "shell", "{\"command\":\"npm test\"}");
        String second = AgentLoopEngine.recoveredToolCallIdentity(71L, 3, "shell", "{\"command\":\"npm test\"}");
        String changed = AgentLoopEngine.recoveredToolCallIdentity(71L, 3, "shell", "{\"command\":\"npm test -- --watch\"}");

        assertThat(first).isEqualTo(second).startsWith("recovered:v1:71:3:shell:");
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    void derivesStableApprovalCreateIdempotencyFromToolCallIdentity() {
        AgentContext context = new AgentContext("session-71", 7, null, "conversation-71", 71L,
                Path.of("."), new ArrayList<>(), 0);

        assertThat(AgentLoopEngine.commandApprovalIdempotencyKey(context, "call-provider-71"))
                .isEqualTo("command-approval:v1:71:session-71:agent_shell:call-provider-71");
    }
}
