package com.labex.labexagent.mcp;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.labexagent.network.OutboundUrlPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpClientWorkerTest {

    @TempDir
    Path workspace;

    @Test
    void startsStdioMcpThroughTheBoundSandboxWorker() throws Exception {
        SandboxWorker worker = mock(SandboxWorker.class);
        SandboxWorker.WorkerProcess process = mock(SandboxWorker.WorkerProcess.class);
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("mcp-contract", workspace);
        when(worker.startProcess(argThat(spec -> spec.equals(run)), argThat(request ->
                request.command().equals(List.of("fake-mcp", "--stdio")) && request.workingDirectory().equals(workspace))))
                .thenReturn(process);
        McpClient client = new McpClient(
                "fake", "stdio", "fake-mcp --stdio", null, Map.of(), worker, run);

        assertSame(process, client.startStdioProcess());
        verify(worker).startProcess(argThat(spec -> spec.equals(run)), argThat(request ->
                request.command().equals(List.of("fake-mcp", "--stdio"))));
    }

    @Test
    void rejectsPrivateHttpMcpEndpointsBeforeOpeningTheConnection() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        McpClient client = new McpClient(
                "private", "http", "http://mcp.example.test/rpc", null, Map.of(), null, null, policy);

        IOException error = assertThrows(IOException.class, client::connect);

        assertTrue(error.getCause() instanceof OutboundUrlPolicy.RejectedOutboundUrlException);
    }
}
