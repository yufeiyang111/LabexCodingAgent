package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.tool.ToolResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    void includesStableToolCallIdentityInTheInitialApprovalEvent() {
        ToolResult approval = ToolResult.commandApprovalRequired(
                "approval required", "approval-61", "git add .", "HIGH", "WRITE", "2026-08-03T12:00:00");

        LinkedHashMap<String, Object> event = AgentLoopEngine.commandApprovalRequiredEvent(
                71L, "session-71", "call-provider-71", "shell", approval, "git add .");

        assertThat(event)
                .containsEntry("approvalId", "approval-61")
                .containsEntry("toolCallId", "call-provider-71")
                .containsEntry("taskId", 71L)
                .containsEntry("sessionId", "session-71");
    }
    @Test
    void derivesStableApprovalCreateIdempotencyFromToolCallIdentity() {
        AgentContext context = new AgentContext("session-71", 7, null, "conversation-71", 71L,
                Path.of("."), new ArrayList<>(), 0);

        assertThat(AgentLoopEngine.commandApprovalIdempotencyKey(context, "call-provider-71"))
                .isEqualTo("command-approval:v1:71:session-71:agent_shell:call-provider-71");
    }
}
