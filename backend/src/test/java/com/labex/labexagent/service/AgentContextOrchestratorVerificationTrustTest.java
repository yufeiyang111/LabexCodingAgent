package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.run.AgentRunExecutionProgressReducer;
import com.labex.labexagent.runtime.AgentContextManager;
import com.labex.labexagent.tool.ToolResult;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentContextOrchestratorVerificationTrustTest {

    @TempDir
    java.nio.file.Path workspace;

    @Test
    void onlyRunTestsCreatesTrustedVerificationEvidence() throws Exception {
        AgentContextOrchestrator orchestrator = new AgentContextOrchestrator(
                mock(AgentContextManager.class), mock(ProjectIndexService.class),
                mock(AgentWorkspaceMemoryService.class), mock(LspSessionManager.class),
                mock(ProjectCodeMapService.class), new AgentRunExecutionProgressReducer());
        StudentProject project = new StudentProject();
        project.setWorkspacePath(Files.createDirectories(workspace).toString());
        AgentContext context = AgentContext.create("session", 1, project, "conversation", 1L);

        orchestrator.afterTool(context, "execute_code", new JsonObject(), ToolResult.ok("exit=0"));

        assertThat(context.getVerificationCount()).isZero();
        assertThat(context.hasTrustedVerification()).isFalse();

        orchestrator.afterTool(context, "run_tests", new JsonObject(), ToolResult.ok("exit=0"));

        assertThat(context.getVerificationCount()).isEqualTo(1);
        assertThat(context.getTrustedVerificationSources()).containsExactly("run_tests");
    }

    @Test
    void transportCompletedNonzeroShellOutcomeMovesProgressIntoRepair() throws Exception {
        AgentContextOrchestrator orchestrator = new AgentContextOrchestrator(
                mock(AgentContextManager.class), mock(ProjectIndexService.class),
                mock(AgentWorkspaceMemoryService.class), mock(LspSessionManager.class),
                mock(ProjectCodeMapService.class), new AgentRunExecutionProgressReducer());
        StudentProject project = new StudentProject();
        project.setWorkspacePath(Files.createDirectories(workspace).toString());
        AgentContext context = AgentContext.create("session", 1, project, "conversation", 1L);
        ToolResult result = ToolResult.fromObservedProcessExecution(
                new ProcessExecutionResult(ExecutionStatus.FAILED, 2, 10L,
                        "exit=2\nTypeScript compile failed", false), "bash", ".", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isSuccessfulExecutionOutcome()).isFalse();

        orchestrator.afterTool(context, "shell", new JsonObject(), result);

        assertThat(context.getStage()).isEqualTo("repair");
        assertThat(context.hasTrustedVerification()).isFalse();
    }
    @Test
    void manualReadWithShaCreatesTrustedVerificationEvidence() throws Exception {
        AgentContextOrchestrator orchestrator = new AgentContextOrchestrator(
                mock(AgentContextManager.class), mock(ProjectIndexService.class),
                mock(AgentWorkspaceMemoryService.class), mock(LspSessionManager.class),
                mock(ProjectCodeMapService.class), new AgentRunExecutionProgressReducer());
        StudentProject project = new StudentProject();
        project.setWorkspacePath(Files.createDirectories(workspace).toString());
        AgentContext context = AgentContext.create("session", 1, project, "conversation", 1L);
        context.applyExecutionProgressProjection("implement", 1, 0, true, java.util.Set.of(),
                java.util.Set.of("package.json"));
        JsonObject args = new JsonObject();
        args.addProperty("file_path", "package.json");

        orchestrator.afterTool(context, "read_file", args,
                ToolResult.ok("[read_file path=package.json sha256=abc]\n{}"));

        assertThat(context.hasUnverifiedChanges()).isFalse();
        assertThat(context.getTrustedVerificationSources()).containsExactly("read_file");
    }
}
