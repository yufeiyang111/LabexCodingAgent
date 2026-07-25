package com.labex.labexagent.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
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
}
