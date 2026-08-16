package com.labex.labexagent.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolAdapterWorkerTest {

    @TempDir
    Path workspace;


    @Test
    void preservesTheMcpJsonSchemaInsteadOfFlatteningEveryArgumentToString() {
        JsonObject inputSchema = JsonParser.parseString("""
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "query": { "type": "string", "description": "Search query" },
                    "limit": { "type": "integer", "minimum": 1 },
                    "filters": {
                      "type": "object",
                      "properties": { "source": { "type": "string" } },
                      "required": ["source"]
                    },
                    "tags": { "type": "array", "items": { "type": "string" } }
                  },
                  "required": ["query", "filters"]
                }
                """).getAsJsonObject();
        McpToolAdapter adapter = new McpToolAdapter(
                new McpToolAdapter.McpToolInfo("docs", "Docs", "search", "Search docs", inputSchema),
                mock(McpManager.class));

        ToolDefinition definition = adapter.definition();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> properties = (java.util.Map<String, Object>) definition.getInputSchema()
                .get("properties");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> limit = (java.util.Map<String, Object>) properties.get("limit");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> filters = (java.util.Map<String, Object>) properties.get("filters");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> tags = (java.util.Map<String, Object>) properties.get("tags");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> tagItems = (java.util.Map<String, Object>) tags.get("items");

        org.junit.jupiter.api.Assertions.assertEquals("integer", limit.get("type"));
        org.junit.jupiter.api.Assertions.assertEquals("object", filters.get("type"));
        org.junit.jupiter.api.Assertions.assertEquals("array", tags.get("type"));
        org.junit.jupiter.api.Assertions.assertEquals("string", tagItems.get("type"));
        org.junit.jupiter.api.Assertions.assertEquals(false, definition.getInputSchema().get("additionalProperties"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("query", "filters"),
                definition.getInputSchema().get("required"));
    }

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
