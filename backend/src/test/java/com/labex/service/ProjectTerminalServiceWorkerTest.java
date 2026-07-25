package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.worker.SandboxWorker;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectTerminalServiceWorkerTest {

    @TempDir
    Path workspace;

    @Test
    void runsSynchronousProjectTerminalCommandsThroughTheSandboxWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "worker output", false));
        ProjectTerminalService service = new ProjectTerminalService(worker);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        ProjectTerminalService.TerminalSession session = service.create(7, project, "Terminal 1", "");

        ProjectTerminalService.TerminalRunResult result = service.run(
                session, project, "echo worker", "", false, 10);

        assertEquals(0, result.exitCode());
        verify(worker).execute(any(), any(), any());
    }

    @Test
    void redactsSensitiveOutputBeforeItEntersTheTerminalSnapshot() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "--token=terminal-sentinel", false));
        ProjectTerminalService service = new ProjectTerminalService(worker);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        ProjectTerminalService.TerminalSession session = service.create(7, project, "Terminal 1", "");

        ProjectTerminalService.TerminalRunResult result = service.run(
                session, project, "echo worker", "", false, 10);

        org.junit.jupiter.api.Assertions.assertFalse(result.output().contains("terminal-sentinel"));
        org.junit.jupiter.api.Assertions.assertFalse(session.snapshot().contains("terminal-sentinel"));
    }
}
