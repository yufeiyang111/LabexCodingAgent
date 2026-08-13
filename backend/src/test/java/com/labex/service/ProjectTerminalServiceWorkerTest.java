package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.worker.SandboxWorker;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertEquals(java.util.List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "echo worker"),
                request.getValue().command());
    }

    @Test
    void keepsCompoundManagedTerminalCommandsAsOneBashPayload() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.shellDescriptor(any())).thenReturn(
                com.labex.labexagent.execution.WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true));
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "installed", false));
        ProjectTerminalService service = new ProjectTerminalService(worker);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        Path frontend = java.nio.file.Files.createDirectories(workspace.resolve("frontend"));
        ProjectTerminalService.TerminalSession session = service.create(7, project, "Terminal 1", "frontend");

        ProjectTerminalService.TerminalRunResult result = service.run(
                session, project, "cd frontend&&npm install", "", false, 240);

        assertEquals(0, result.exitCode());
        ArgumentCaptor<ProcessExecutionRequest> request = ArgumentCaptor.forClass(ProcessExecutionRequest.class);
        verify(worker).execute(any(), request.capture(), any());
        assertEquals(java.util.List.of("/bin/bash", "--noprofile", "--norc", "-lc", "cd frontend&&npm install"),
                request.getValue().command());
        assertEquals(frontend, request.getValue().workingDirectory());
        assertEquals(java.time.Duration.ofSeconds(240), request.getValue().timeout());
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

    @Test
    void longRunningTerminalConvergesToSucceededWithDurationAndOutputChars() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.shellDescriptor(any())).thenReturn(
                WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true));
        when(worker.startProcess(any(), any())).thenReturn(new FakeWorkerProcess("building\ndone\n", 0));
        ProjectTerminalService service = new ProjectTerminalService(worker);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        ProjectTerminalService.TerminalSession session = service.create(7, project, "Terminal 1", "");

        ProjectTerminalService.TerminalRunResult result = service.run(
                session, project, "npm run build", "", true, 240);

        assertTrue(result.running());
        awaitConvergence(session);
        assertFalse(session.running);
        assertEquals("succeeded", session.lastExecution.status());
        assertEquals(0, session.exitCode);
        assertTrue(session.lastExecution.outputChars() > 0, () -> String.valueOf(session.lastExecution));
        assertEquals(240, session.lastExecution.timeoutSeconds());
        assertEquals("bash", session.lastExecution.shell());
    }

    @Test
    void longRunningTerminalConvergesToFailedWithNonZeroExitCode() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.shellDescriptor(any())).thenReturn(
                WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true));
        when(worker.startProcess(any(), any())).thenReturn(new FakeWorkerProcess("compile error\n", 1));
        ProjectTerminalService service = new ProjectTerminalService(worker);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        ProjectTerminalService.TerminalSession session = service.create(7, project, "Terminal 1", "");

        service.run(session, project, "mvn test", "", true, 240);

        awaitConvergence(session);
        assertEquals("failed", session.lastExecution.status());
        assertEquals(1, session.exitCode);
        assertTrue(session.lastExecution.outputChars() > 0, () -> String.valueOf(session.lastExecution));
    }

    @Test
    void stopKeepsCancelledStatusEvenAfterTheOutputReaderConverges() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.shellDescriptor(any())).thenReturn(
                WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true));
        BlockingWorkerProcess process = new BlockingWorkerProcess();
        when(worker.startProcess(any(), any())).thenReturn(process);
        ProjectTerminalService service = new ProjectTerminalService(worker);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        ProjectTerminalService.TerminalSession session = service.create(7, project, "Terminal 1", "");

        service.run(session, project, "npm run dev", "", true, 240);

        service.stop(session);
        assertEquals("cancelled", session.lastExecution.status());

        awaitConvergence(session);
        Thread.sleep(200L);
        assertEquals("cancelled", session.lastExecution.status(),
                "reader convergence must not overwrite the cancelled terminal state");
    }

    @Test
    void stopOnIdleSessionDoesNotClobberThePreviousResult() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.execute(any(), any(), any())).thenReturn(new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 1, "worker output", false));
        ProjectTerminalService service = new ProjectTerminalService(worker);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        ProjectTerminalService.TerminalSession session = service.create(7, project, "Terminal 1", "");

        service.run(session, project, "echo worker", "", false, 10);
        service.stop(session);

        assertEquals(0, session.exitCode);
        assertEquals("succeeded", session.lastExecution.status());
    }

    private void awaitConvergence(ProjectTerminalService.TerminalSession session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (session.lastExecution != null && "running".equals(session.lastExecution.status())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        String status = session.lastExecution == null ? "missing" : session.lastExecution.status();
        assertFalse("running".equals(status), "terminal execution did not converge within the test deadline");
    }

    private static final class FakeWorkerProcess implements SandboxWorker.WorkerProcess {
        private final InputStream output;
        private final int exitCode;

        private FakeWorkerProcess(String output, int exitCode) {
            this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        @Override
        public InputStream standardOutput() {
            return output;
        }

        @Override
        public InputStream standardError() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream standardInput() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public long processId() {
            return 1L;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public Integer exitCode() {
            return exitCode;
        }

        @Override
        public void terminate() {
        }
    }

    private static final class BlockingWorkerProcess implements SandboxWorker.WorkerProcess {
        private final java.util.concurrent.CountDownLatch terminated = new java.util.concurrent.CountDownLatch(1);
        private volatile boolean alive = true;
        private final InputStream output = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                try {
                    terminated.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };

        @Override
        public InputStream standardOutput() {
            return output;
        }

        @Override
        public InputStream standardError() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream standardInput() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public long processId() {
            return 1L;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public Integer exitCode() {
            return 143;
        }

        @Override
        public void terminate() {
            alive = false;
            terminated.countDown();
        }
    }
}
