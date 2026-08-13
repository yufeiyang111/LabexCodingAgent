package com.labex.labexagent.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LspSessionManagerWorkerTest {

    @TempDir
    Path workspace;

    @Test
    void delegatesLanguageServerStartupAndWorkspaceUrisToTheWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        SandboxWorker.WorkerProcess process = mock(SandboxWorker.WorkerProcess.class);
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("lsp-contract", workspace);
        Path file = workspace.resolve("Example.java");
        when(worker.startProcess(argThat(spec -> spec.equals(run)), argThat(request ->
                request.command().equals(List.of("fake-java-lsp")) && request.workingDirectory().equals(workspace))))
                .thenReturn(process);
        when(worker.workspaceUri(run, file)).thenReturn("file:///workspace/Example.java");
        LspSessionManager manager = new LspSessionManager(worker);

        assertSame(process, manager.startWorkerProcess(run, List.of("fake-java-lsp")));
        assertEquals("file:///workspace/Example.java", manager.workspaceUri(run, file));
        verify(worker).startProcess(argThat(spec -> spec.equals(run)), argThat(request ->
                request.command().equals(List.of("fake-java-lsp"))));
    }
    @Test
    void linuxWorkerFallsBackFromWindowsCommandOverrides() {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        LspSessionManager manager = new LspSessionManager(worker);

        assertEquals(List.of("typescript-language-server", "--stdio"),
                manager.resolveConfiguredCommand(
                        "C:\\Users\\demo\\typescript-language-server.cmd --stdio",
                        "typescript-language-server --stdio"));
    }

    @Test
    void javaWorkspaceDataDirectoryUsesWorkerPath() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.workspaceUri(any(WorkerRunSpec.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path path = invocation.getArgument(1);
                    String relative = workspace.relativize(path).toString().replace('\\', '/');
                    return "file:///workspace/" + relative;
                });
        LspSessionManager manager = new LspSessionManager(worker);

        List<String> command = manager.commandForFile(workspace, workspace.resolve("Example.java"));

        assertEquals("-data", command.get(command.size() - 2));
        assertEquals("/workspace/.labexagent/jdtls-workspace", command.get(command.size() - 1));
    }

    @Test
    void timeoutFailureMessageNeverCollapsesToNull() {
        LspSessionManager manager = new LspSessionManager(mock(SandboxWorker.class));

        assertEquals("TimeoutException", manager.message(new java.util.concurrent.TimeoutException()));
    }

    @Test
    void probeExecutableRejectsMissingCommandInLinuxWorker() {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("lsp-probe", workspace);
        when(worker.execute(argThat(spec -> spec.equals(run)), argThat(request ->
                request.command().equals(List.of("sh", "-c", "command -v 'jdtls'"))), any()))
                .thenReturn(new ProcessExecutionResult(
                        ExecutionStatus.FAILED, 1, 5L, "", false));
        LspSessionManager manager = new LspSessionManager(worker);

        assertFalse(manager.probeExecutable(run, List.of("jdtls", "--stdio")));
    }

    @Test
    void probeExecutableAcceptsExistingCommandInLinuxWorker() {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("lsp-probe", workspace);
        when(worker.execute(argThat(spec -> spec.equals(run)), argThat(request ->
                request.command().equals(List.of("sh", "-c", "command -v 'typescript-language-server'"))), any()))
                .thenReturn(new ProcessExecutionResult(
                        ExecutionStatus.SUCCEEDED, 0, 5L,
                        "/usr/local/bin/typescript-language-server", false));
        LspSessionManager manager = new LspSessionManager(worker);

        assertTrue(manager.probeExecutable(run, List.of("typescript-language-server", "--stdio")));
    }

    @Test
    void probeExecutableSkipsUnsafeOrEmptyExecutables() {
        LspSessionManager manager = new LspSessionManager(mock(SandboxWorker.class));

        assertTrue(manager.probeExecutable(WorkerRunSpec.forWorkspace("lsp-probe", workspace), List.of()));
        assertTrue(manager.probeExecutable(
                WorkerRunSpec.forWorkspace("lsp-probe", workspace), List.of("jdtls --data; rm -rf /")));
    }

    @Test
    void prewarmSkipsMissingExecutableWithoutStartingProcess() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        when(worker.usesLinuxShell()).thenReturn(true);
        when(worker.execute(any(WorkerRunSpec.class), argThat(request ->
                request.command().equals(List.of("sh", "-c", "command -v 'jdtls'"))), any()))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.FAILED, 1, 5L, "", false));
        LspSessionManager manager = new LspSessionManager(worker);

        manager.prewarm(workspace, "java");

        verify(worker, never()).startProcess(any(), any());
    }

    @Test
    void prewarmIgnoresUnsupportedLanguageWithoutProbing() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        LspSessionManager manager = new LspSessionManager(worker);

        manager.prewarm(workspace, "cobol");

        verify(worker, never()).execute(any(), any(), any());
        verify(worker, never()).startProcess(any(), any());
    }

    @Test
    void diagnosticsDisabledByConfigReturnUnavailableWithoutStartingProcess() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        LspSessionManager manager = new LspSessionManager(worker);
        java.lang.reflect.Field enabled = LspSessionManager.class.getDeclaredField("lspEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(manager, false);
        Path file = workspace.resolve("Example.java");
        Files.writeString(file, "class Example {}");

        LspSessionManager.LspDiagnosticsResult result = manager.diagnostics(workspace, file);

        assertFalse(result.available());
        assertTrue(result.message().contains("disabled"));
        verify(worker, never()).execute(any(), any(), any());
        verify(worker, never()).startProcess(any(), any());
    }
}
