package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.runtime.AgentContext;
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
                mock(ProjectCodeMapService.class));
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
}