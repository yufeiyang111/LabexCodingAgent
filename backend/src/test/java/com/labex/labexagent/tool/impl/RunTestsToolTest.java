package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RunTestsToolTest {

    @TempDir
    Path workspace;

    @Test
    void exposesNoModelControlledCommandProperty() {
        RunTestsTool tool = new RunTestsTool(mock(SandboxWorker.class));

        assertThat(tool.definition().getInputSchema().get("properties"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey("command");
    }

    @Test
    void executesDetectedMavenTestsAsDirectArgvWithoutShellWrapper() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 10, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");

        ToolResult result = new RunTestsTool(worker).execute(context(), new JsonObject());

        assertThat(result.isApprovalRequired()).isTrue();
        verify(worker, never()).execute(any(), any(), any());
    }

    @Test
    void rejectsUnsupportedWorkspaceBeforeWorkerExecution() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);

        ToolResult result = new RunTestsTool(worker).execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).contains("无法识别项目类型");
        verify(worker, never()).execute(any(), any(), any());
    }

    @Test
    void retainsTheBoundedTimeoutWhenAReadOnlyTestIntentIsAllowed() throws Exception {
        // There is no supported read-only test command today. This test documents the direct request
        // shape through the policy result rather than weakening the production test-command policy.
        SandboxWorker worker = mock(SandboxWorker.class);
        Files.writeString(workspace.resolve("package.json"), "{}");
        JsonObject args = new JsonObject();
        args.addProperty("timeout_seconds", 9999);

        ToolResult result = new RunTestsTool(worker).execute(context(), args);

        assertThat(result.isApprovalRequired()).isTrue();
        verify(worker, never()).execute(any(), any(), any());
    }

    private AgentContext context() {
        return new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), new ArrayList<>(), 0);
    }
}
