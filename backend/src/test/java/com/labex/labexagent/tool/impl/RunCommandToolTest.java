package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.run.AgentVerificationRecorder;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.worker.SandboxWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RunCommandToolTest {
    @TempDir
    Path workspace;

    @Test
    void neverCreatesAnUnpersistedApprovalInsideTheTool() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        AgentContext context = new AgentContext(
                "session-1", 7, null, "conversation-1", 71L, workspace, new ArrayList<>(), 0);
        JsonObject args = new JsonObject();
        args.addProperty("command", "rm -rf unsafe");

        ToolResult result = new RunCommandTool(worker).execute(context, args);

        assertThat(result.isApprovalRequired()).isFalse();
        assertThat(result.getApprovalId()).isNull();
        assertThat(result.getContent()).contains("command_approval_not_persisted");
        verify(worker, never()).execute(any(), any(), any());
    }
    @Test
    void projectsARealShellTestCommandIntoDurableVerificationEvidence() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 5, "tests passed", false));
        AgentVerificationRecorder verificationRecorder = mock(AgentVerificationRecorder.class);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        AgentContext context = new AgentContext(
                "session-1", 7, project, "conversation-1", 71L, workspace, new ArrayList<>(), 0);
        JsonObject args = new JsonObject();
        args.addProperty("command", "npm test");
        AgentExecutionProperties safe = new AgentExecutionProperties();
        safe.setPermissionProfile("safe");

        ToolResult result = new RunCommandTool(worker, safe, verificationRecorder).execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        verify(verificationRecorder).recordShellToolResult(
                eq(71L), eq(7), eq(12), eq("npm test"), any(ToolResult.class));
    }

    @Test
    void safeProfileKeepsLegacyCdPrefixCompatibility() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 5, "ok", false));
        Path frontend = Files.createDirectories(workspace.resolve("frontend"));
        AgentContext context = new AgentContext(
                "session-1", 7, null, "conversation-1", 71L, workspace, new ArrayList<>(), 0);
        JsonObject args = new JsonObject();
        args.addProperty("command", "cd frontend && git status");
        AgentExecutionProperties safe = new AgentExecutionProperties();
        safe.setPermissionProfile("safe");

        ToolResult result = new RunCommandTool(worker, safe).execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertThat(request.getValue().command()).isEqualTo(List.of("git", "status"));
        assertThat(request.getValue().workingDirectory()).isEqualTo(frontend);
    }

}
