package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorkspaceMemoryServiceTest {

    @TempDir
    Path workspace;

    @Test
    void recordsTransportCompletedNonzeroShellAsFailedVerificationAndFailureMemory() throws Exception {
        StudentProject project = new StudentProject();
        project.setWorkspacePath(Files.createDirectories(workspace).toString());
        AgentContext context = AgentContext.create("session", 7, project, "conversation", 1L);
        JsonObject args = new JsonObject();
        args.addProperty("command", "npm run build");
        ToolResult result = ToolResult.fromObservedProcessExecution(
                new ProcessExecutionResult(ExecutionStatus.FAILED, 2, 10L,
                        "exit=2\nTypeScript compile failed", false), "bash", ".", null);

        AgentWorkspaceMemoryService service = new AgentWorkspaceMemoryService();
        service.recordToolResult(context, "shell", args, result);

        AgentWorkspaceMemoryService.WorkspaceMemory memory = service.readMemory(project);
        assertThat(memory.verifications).hasSize(1);
        assertThat(memory.verifications.get(0).summary).startsWith("FAIL shell");
        assertThat(memory.failures).hasSize(1);
        assertThat(memory.failures.get(0).summary).contains("shell", "failed");
    }
}
