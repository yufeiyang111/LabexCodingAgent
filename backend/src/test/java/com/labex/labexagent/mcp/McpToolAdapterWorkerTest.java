package com.labex.labexagent.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolAdapterWorkerTest {

    @TempDir
    Path workspace;

    @Test
    void bindsMcpCallsToTheAgentWorkspaceWorkerRun() {
        McpManager manager = mock(McpManager.class);
        McpToolAdapter adapter = new McpToolAdapter(
                new McpToolAdapter.McpToolInfo("docs", "Docs", "search", "Search docs", new JsonObject()), manager);
        AgentContext context = new AgentContext(
                "session-17", 7, null, "conversation-17", 17L, workspace,
                new ArrayList<>(), 0);
        when(manager.callTool(eq(7), argThat(run -> run.workspaceRoot().equals(workspace)),
                eq("docs"), eq("search"), eq("{}")))
                .thenReturn(McpClient.CallResult.ok("ok"));

        assertTrue(adapter.execute(context, new JsonObject()).isSuccess());

        verify(manager).callTool(eq(7), argThat(run -> run.workspaceRoot().equals(workspace)),
                eq("docs"), eq("search"), eq("{}"));
    }
}
