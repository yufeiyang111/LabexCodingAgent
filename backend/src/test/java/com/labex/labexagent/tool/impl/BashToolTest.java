package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class BashToolTest {

    @TempDir
    Path workspace;

    @Test
    void blankCommandFailsWithoutCallingWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);

        ToolResult result = new BashTool(worker).execute(context("session-1", 1L), commandArgs("   "));

        assertFalse(result.isSuccess());
        assertEquals("command is required", result.getContent());
        verifyNoInteractions(worker);
    }

    @Test
    void hardBlockedCommandFailsWithoutApprovalOrWorkerCall() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);

        ToolResult result = new BashTool(worker).execute(context("session-2", 2L), commandArgs("shutdown now"));

        assertFalse(result.isSuccess());
        assertFalse(result.isApprovalRequired());
        verifyNoInteractions(worker);
    }

    @Test
    void hardBlockedCommandRemainsBlockedWhenDangerousFlagIsSet() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        JsonObject args = commandArgs("shutdown now");
        args.addProperty("allow_dangerous", true);

        ToolResult result = new BashTool(worker).execute(context("session-3", 3L), args);

        assertFalse(result.isSuccess());
        assertFalse(result.isApprovalRequired());
        verifyNoInteractions(worker);
    }

    @Test
    void riskyCommandRequiresApprovalWithoutCallingWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        String command = "rm -rf foo";

        ToolResult result = new BashTool(worker).execute(context("session-4", 4L), commandArgs(command));

        assertFalse(result.isSuccess());
        assertTrue(result.isApprovalRequired());
        assertEquals(command, result.getApprovalCommand());
        verifyNoInteractions(worker);
    }

    @Test
    void riskyCommandWithDangerousFlagStillRequiresApprovalAndDoesNotCallWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        String command = "rm -rf foo";
        JsonObject args = commandArgs(command);
        args.addProperty("allow_dangerous", true);

        ToolResult result = new BashTool(worker).execute(context("session-5", 5L), args);

        assertFalse(result.isSuccess());
        assertTrue(result.isApprovalRequired());
        assertEquals(command, result.getApprovalCommand());
        verifyNoInteractions(worker);
    }

    @Test
    void omittingTimeoutUsesSixtySeconds() throws Exception {
        SandboxWorker worker = successfulWorker();

        new BashTool(worker).execute(context("session-6", 6L), commandArgs("echo timeout"));

        assertEquals(Duration.ofSeconds(60), capturedRequest(worker).timeout());
    }

    @Test
    void inRangeTimeoutIsPreserved() throws Exception {
        SandboxWorker worker = successfulWorker();
        JsonObject args = commandArgs("echo preserved");
        args.addProperty("timeout_seconds", 42);

        new BashTool(worker).execute(context("session-6-in-range", 6L), args);

        assertEquals(Duration.ofSeconds(42), capturedRequest(worker).timeout());
    }

    @Test
    void zeroAndNegativeTimeoutsClampToOneSecond() throws Exception {
        SandboxWorker zeroWorker = successfulWorker();
        JsonObject zeroArgs = commandArgs("echo zero");
        zeroArgs.addProperty("timeout_seconds", 0);

        new BashTool(zeroWorker).execute(context("session-7", 7L), zeroArgs);

        assertEquals(Duration.ofSeconds(1), capturedRequest(zeroWorker).timeout());

        SandboxWorker negativeWorker = successfulWorker();
        JsonObject negativeArgs = commandArgs("echo negative");
        negativeArgs.addProperty("timeout_seconds", -5);

        new BashTool(negativeWorker).execute(context("session-8", 8L), negativeArgs);

        assertEquals(Duration.ofSeconds(1), capturedRequest(negativeWorker).timeout());
    }

    @Test
    void timeoutAboveMaximumClampsToSixHundredSeconds() throws Exception {
        SandboxWorker worker = successfulWorker();
        JsonObject args = commandArgs("echo maximum");
        args.addProperty("timeout_seconds", 601);

        new BashTool(worker).execute(context("session-9", 9L), args);

        assertEquals(Duration.ofSeconds(600), capturedRequest(worker).timeout());
    }

    @Test
    void directCommandUsesArgvWithoutAShellWrapper() throws Exception {
        SandboxWorker worker = successfulWorker();
        String command = "echo linux";

        new BashTool(worker).execute(context("session-10", 10L), commandArgs(command));

        assertEquals(List.of("echo", "linux"), capturedRequest(worker).command());
    }

    @Test
    void directCommandDoesNotDependOnWorkerShellSelection() throws Exception {
        SandboxWorker worker = successfulWorker();
        when(worker.usesLinuxShell()).thenReturn(false);
        String command = "echo platform";

        new BashTool(worker).execute(context("session-11", 11L), commandArgs(command));

        assertEquals(List.of("echo", "platform"), capturedRequest(worker).command());
    }

    @Test
    void taskIdCreatesTaskRunId() throws Exception {
        SandboxWorker worker = successfulWorker();

        new BashTool(worker).execute(context("session-12", 8L), commandArgs("echo run-id"));

        ArgumentCaptor<WorkerRunSpec> run = ArgumentCaptor.forClass(WorkerRunSpec.class);
        verify(worker).execute(run.capture(), any(ProcessExecutionRequest.class), any(CancellationToken.class));
        assertEquals("task-8", run.getValue().runId());
        verifyNoUnexpectedWorkerInteractions(worker);
    }

    @Test
    void missingTaskIdCreatesSessionRunId() throws Exception {
        SandboxWorker worker = successfulWorker();

        new BashTool(worker).execute(context("session-null", null), commandArgs("echo run-id"));

        ArgumentCaptor<WorkerRunSpec> run = ArgumentCaptor.forClass(WorkerRunSpec.class);
        verify(worker).execute(run.capture(), any(ProcessExecutionRequest.class), any(CancellationToken.class));
        assertEquals("agent-session-null", run.getValue().runId());
        verifyNoUnexpectedWorkerInteractions(worker);
    }

    @Test
    void activeCancellationTokenIsForwardedByIdentity() throws Exception {
        SandboxWorker worker = successfulWorker();
        CancellationToken token = mock(CancellationToken.class);
        AgentContext context = context("session-13", 13L);
        context.setCancellationToken(token);

        new BashTool(worker).execute(context, commandArgs("echo cancel"));

        ArgumentCaptor<CancellationToken> capturedToken = ArgumentCaptor.forClass(CancellationToken.class);
        verify(worker).execute(any(WorkerRunSpec.class), any(ProcessExecutionRequest.class), capturedToken.capture());
        assertSame(token, capturedToken.getValue());
        verifyNoUnexpectedWorkerInteractions(worker);
    }

    @Test
    void failedWorkerResultMapsToUnsuccessfulToolResultWithStatusAndExit() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.FAILED, 1, 3, "failure", false));

        ToolResult result = new BashTool(worker).execute(context("session-14", 14L), commandArgs("echo fail"));

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("status=failed"));
        assertTrue(result.getContent().contains("exit=1"));
        verify(worker).execute(any(WorkerRunSpec.class), any(ProcessExecutionRequest.class), any(CancellationToken.class));
        verifyNoMoreInteractions(worker);
    }

    @Test
    void shellOperatorFailureExplainsHowToRecover() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        AgentContext context = context("session-shell-policy", 15L);

        ToolResult result = new RunCommandTool(worker).execute(context, commandArgs("echo one | cat"));

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("reason=shell_operator"));
        assertTrue(result.getContent().contains("\u8bf7\u62c6\u5206\u4e3a\u591a\u4e2a\u72ec\u7acb\u5de5\u5177\u8c03\u7528"));
        verifyNoInteractions(worker);
    }

    private SandboxWorker successfulWorker() {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "ok", false));
        return worker;
    }

    private ProcessExecutionRequest capturedRequest(SandboxWorker worker) {
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(WorkerRunSpec.class), request.capture(), any(CancellationToken.class));
        verifyNoUnexpectedWorkerInteractions(worker);
        return request.getValue();
    }

    private void verifyNoUnexpectedWorkerInteractions(SandboxWorker worker) {
        verifyNoMoreInteractions(worker);
    }

    private AgentContext context(String sessionId, Long taskId) {
        return new AgentContext(
                sessionId, 1, null, "conversation", taskId, workspace,
                new ArrayList<>(), new ArrayList<>(), 0);
    }

    private JsonObject commandArgs(String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        return args;
    }
}
