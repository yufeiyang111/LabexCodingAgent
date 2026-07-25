package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.worker.SandboxWorker;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ExecuteCodeToolSandboxPathTest {

    @TempDir
    Path workspace;

    @Test
    void passesTheTemporaryScriptAsASandboxPathForLinuxWorkers() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "ok", false));
        JsonObject args = new JsonObject();
        args.addProperty("language", "javascript");
        args.addProperty("code", "console.log('ok')");

        var result = new ExecuteCodeTool(worker).execute(context(), args);

        assertTrue(result.isSuccess());
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertEquals("node", request.getValue().command().get(0));
        assertTrue(request.getValue().command().get(1).startsWith("/workspace/.labex-agent/temp"));
        assertTrue(request.getValue().command().get(1).endsWith(".js"));
    }

    @Test
    void blocksInterpreterCodeThatCanSpawnShellCommands() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        JsonObject args = new JsonObject();
        args.addProperty("language", "javascript");
        args.addProperty("code", "require('child_process').execSync('mvn test')");

        var result = new ExecuteCodeTool(worker).execute(context(), args);

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("restricted execution capability"));
        verifyNoInteractions(worker);
    }

    private AgentContext context() {
        return new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), new ArrayList<>(), 0);
    }
}
