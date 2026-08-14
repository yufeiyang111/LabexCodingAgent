package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.labex.labexagent.dto.AgentStreamRequest;
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
    void rejectsApprovalProjectionWithoutPersistedApprovalId() {
        ToolResult approval = ToolResult.approvalRequired("approval required", "mvn test");

        assertThatThrownBy(() -> AgentLoopEngine.commandApprovalRequiredEvent(
                71L, "session-71", "call-provider-71", "run_tests", approval, "mvn test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persisted approvalId");
    }

    @Test
    void collapsesDifferentVerificationStrategiesWhenTheyResolveToTheSameCommand() {
        JsonObject build = new JsonObject();
        build.addProperty("strategy", "build");
        JsonObject compile = new JsonObject();
        compile.addProperty("strategy", "compile");

        JsonObject normalizedBuild = AgentLoopEngine.commandLoopArguments(
                "run_tests", build, "npm run build", "frontend", false);
        JsonObject normalizedCompile = AgentLoopEngine.commandLoopArguments(
                "run_tests", compile, "npm run build", "frontend", false);
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());

        assertThat(guard.beforeToolCall("run_tests", normalizedBuild).signature())
                .isEqualTo(guard.beforeToolCall("run_tests", normalizedCompile).signature());
    }

    @Test
    void keepsPreviewPortAndWorkdirInTheCommandLoopIdentity() {
        JsonObject firstArguments = new JsonObject();
        firstArguments.addProperty("port", 3000);
        firstArguments.addProperty("workdir", "frontend");
        JsonObject secondArguments = new JsonObject();
        secondArguments.addProperty("port", 4173);
        secondArguments.addProperty("workdir", "frontend");

        JsonObject first = AgentLoopEngine.commandLoopArguments(
                "start_preview", firstArguments, "npm run dev", "frontend", true);
        JsonObject second = AgentLoopEngine.commandLoopArguments(
                "start_preview", secondArguments, "npm run dev", "frontend", true);
        AgentLoopGuard guard = new AgentLoopGuard(new AgentLoopProperties());

        assertThat(first.has("port")).isTrue();
        assertThat(first.get("port").getAsInt()).isEqualTo(3000);
        assertThat(AgentLoopEngine.commandWorkingDirectory(
                "start_preview", Path.of("."), firstArguments)).isEqualTo("frontend");
        assertThat(guard.beforeToolCall("start_preview", first).signature())
                .isNotEqualTo(guard.beforeToolCall("start_preview", second).signature());
    }

    @Test
    void recognizesAnExplicitLoopGuardRetryAnswerAsACommandFailureReset() {
        assertThat(AgentLoopEngine.isCommandFailureResetRequest(
                "User response payload: {\"answer\":\"允许重新尝试该调用\"}"))
                .isTrue();
        assertThat(AgentLoopEngine.isCommandFailureResetRequest(
                "User response payload: {\"answer\":\"Allow this call to be retried\"}"))
                .isTrue();
    }

    @Test
    void recognizesEnvironmentRecoveryStoredInInternalResumeNoteWithoutChangingUserMessage() {
        AgentStreamRequest request = new AgentStreamRequest();
        request.setMessage("[acceptance:environment-wait]");
        request.setResumeNote("Dependency environment is restored; retry the existing task");

        assertThat(AgentLoopEngine.isCommandFailureResetRequest(request)).isTrue();
    }

    @Test
    void derivesStableApprovalCreateIdempotencyFromToolCallIdentity() {
        AgentContext context = new AgentContext("session-71", 7, null, "conversation-71", 71L,
                Path.of("."), new ArrayList<>(), 0);

        assertThat(AgentLoopEngine.commandApprovalIdempotencyKey(context, "call-provider-71"))
                .isEqualTo("command-approval:v1:71:session-71:agent_shell:call-provider-71");
    }
}
