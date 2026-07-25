package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.worker.SandboxWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RunCommandToolWorkingDirectoryTest {

    @TempDir
    Path workspace;

    @Test
    void runsADirectCommandInTheRequestedSafeSubdirectoryWithoutShellChaining() throws Exception {
        Path frontend = Files.createDirectories(workspace.resolve("frontend"));
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "ok", false));
        JsonObject args = new JsonObject();
        args.addProperty("command", "git status");
        args.addProperty("working_directory", "frontend");

        var result = new RunCommandTool(worker).execute(context(), args);

        assertTrue(result.isSuccess());
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertTrue(request.getValue().workingDirectory().equals(frontend));
    }

    private AgentContext context() {
        return new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), new ArrayList<>(), 0);
    }
}
