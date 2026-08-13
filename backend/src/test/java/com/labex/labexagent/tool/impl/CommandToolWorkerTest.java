package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class CommandToolWorkerTest {

    @TempDir
    Path workspace;

    @Test
    void commandToolsExecuteThroughTheWorkspaceWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        AgentContext context = new AgentContext(
                "session-7", 1, null, "conversation-7", 1L, workspace,
                new ArrayList<>(), 0);

        assertSuccessful(new RunCommandTool(worker), context, commandArgs("echo worker"));
        assertSuccessful(new BashTool(worker), context, commandArgs("echo worker"));
        // run_tests 的服务端验证命令（mvn）需要 runtime 持久化审批后才能执行，工具直连必须拒绝。
        ToolResult verification = new RunTestsTool(worker).execute(context, new JsonObject());
        assertFalse(verification.isSuccess());
        assertTrue(verification.getContent().contains("runtime_protocol_error=command_approval_not_persisted"));
        assertSuccessful(new ExecuteCodeTool(worker), context, codeArgs());

        verify(worker, times(3)).execute(any(), any(), any());
    }

    @Test
    void dockerWorkerUsesALinuxShellEvenWhenTheControlPlaneRunsOnWindows() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "ok", false));
        AgentContext context = new AgentContext(
                "session-8", 1, null, "conversation-8", 8L, workspace,
                new ArrayList<>(), 0);

        assertSuccessful(new RunCommandTool(worker), context, commandArgs("echo worker"));

        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertEquals(java.util.List.of("/bin/bash", "--noprofile", "--norc", "-lc", "echo worker"),
                request.getValue().command());
    }

    @Test
    void commandToolsForwardTheActiveRunCancellationTokenToTheWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "ok", false));
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        AgentContext context = new AgentContext(
                "session-9", 1, null, "conversation-9", 9L, workspace,
                new ArrayList<>(), 0);
        AgentCancellationRegistry.ActiveRun run = new AgentCancellationRegistry()
                .register("session-9", 1, 12, 9L);
        context.setCancellationToken(run);

        assertSuccessful(new RunCommandTool(worker), context, commandArgs("echo worker"));
        assertSuccessful(new BashTool(worker), context, commandArgs("echo worker"));
        // run_tests 的服务端验证命令需要 runtime 审批；未经审批的工具直连必须拒绝且不得转发 token。
        ToolResult verification = new RunTestsTool(worker).execute(context, new JsonObject());
        assertFalse(verification.isSuccess());
        assertSuccessful(new ExecuteCodeTool(worker), context, codeArgs());

        ArgumentCaptor<CancellationToken> tokenCaptor = ArgumentCaptor.forClass(CancellationToken.class);
        verify(worker, times(3)).execute(any(), any(), tokenCaptor.capture());
        assertTrue(tokenCaptor.getAllValues().stream().allMatch(token -> token == run));
    }

    @Test
    void shellToolIgnoresModelDangerousFlagForRiskyCommands() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        AgentContext context = new AgentContext(
                "session-risk", 1, null, "conversation-risk", 10L, workspace,
                new ArrayList<>(), 0);
        JsonObject args = commandArgs("rm -rf unsafe");
        args.addProperty("allow_dangerous", true);

        ToolResult result = new RunCommandTool(worker).execute(context, args);
        assertFalse(result.isSuccess());
        assertFalse(result.isApprovalRequired());
        assertTrue(result.getContent().contains("runtime_protocol_error=command_approval_not_persisted"));
        verify(worker, times(0)).execute(any(), any(), any());
    }

    private void assertSuccessful(AgentTool tool, AgentContext context, JsonObject args) throws Exception {
        assertTrue(tool.execute(context, args).isSuccess());
    }

    private JsonObject commandArgs(String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        return args;
    }

    private JsonObject codeArgs() {
        JsonObject args = new JsonObject();
        args.addProperty("language", "python");
        args.addProperty("code", "print('worker')");
        return args;
    }
}
