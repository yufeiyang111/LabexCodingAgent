package com.labex.labexagent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.Gson;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceOperationIdentityTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path workspace;

    @Test
    void projectsStableSafeIdentityWithoutLeakingTheHostWorkspacePath() throws Exception {
        Path nestedWorkdir = Files.createDirectories(workspace.resolve("src/service"));
        AgentContext context = context(71L, 4L);

        WorkspaceOperationIdentity first = WorkspaceOperationIdentity.forContext(
                context, nestedWorkdir, List.of("src/service/Task.java", "README.md", "src/service/Task.java"));
        WorkspaceOperationIdentity repeated = WorkspaceOperationIdentity.forContext(
                context, nestedWorkdir, List.of("README.md", "src/service/Task.java"));
        AgentContext nextEpoch = context(71L, 5L);
        WorkspaceOperationIdentity next = WorkspaceOperationIdentity.forContext(
                nextEpoch, nestedWorkdir, List.of("README.md", "src/service/Task.java"));

        Map<String, Object> payload = first.toPayload();

        assertEquals(1, payload.get("schemaVersion"));
        assertEquals(7, payload.get("studentId"));
        assertEquals(12, payload.get("projectId"));
        assertEquals("conversation-71", payload.get("conversationId"));
        assertEquals(71L, payload.get("taskId"));
        assertEquals(4L, payload.get("executionEpoch"));
        assertEquals("src/service", payload.get("workingDirectory"));
        assertEquals(List.of("README.md", "src/service/Task.java"), payload.get("relativePaths"));
        assertEquals(first.operationFingerprint(), repeated.operationFingerprint());
        assertNotEquals(first.operationFingerprint(), next.operationFingerprint());
        assertFalse(GSON.toJson(payload).contains(workspace.toAbsolutePath().normalize().toString()));
    }

    private AgentContext context(Long taskId, long epoch) {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        AgentContext context = AgentContext.create("session-71", 7, project, "conversation-71", taskId);
        context.setExecutionEpoch(epoch);
        return context;
    }
}
