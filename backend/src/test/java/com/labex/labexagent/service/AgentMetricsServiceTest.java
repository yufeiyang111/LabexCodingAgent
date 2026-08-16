package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentMetricsServiceTest {

    @TempDir
    Path workspace;

    @Test
    void separatesTransportCompletionFromActualExecutionFailureInMetrics() throws Exception {
        StudentProject project = new StudentProject();
        project.setWorkspacePath(Files.createDirectories(workspace).toString());
        AgentContext context = AgentContext.create("session", 7, project, "conversation", 1L);
        JsonObject args = new JsonObject();
        args.addProperty("command", "npm run build");
        ToolResult result = ToolResult.fromObservedProcessExecution(
                new ProcessExecutionResult(ExecutionStatus.FAILED, 2, 10L,
                        "exit=2\nTypeScript compile failed", false), "bash", ".", null);

        new AgentMetricsService().recordTool(context, "shell", args, result, 10L,
                AgentPostEditHookService.HookReport.empty());

        JsonObject event = JsonParser.parseString(
                Files.readString(workspace.resolve(".labex/agent-metrics.jsonl"))).getAsJsonObject();
        assertThat(event.get("success").getAsBoolean()).isFalse();
        assertThat(event.get("transportSuccess").getAsBoolean()).isTrue();
        assertThat(event.get("executionSuccess").getAsBoolean()).isFalse();
    }
}
