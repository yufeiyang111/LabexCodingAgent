package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFileToolTest {

    @TempDir
    Path workspace;

    @Test
    void returnsSafeCandidatesWhenTheRequestedWorkspaceFileDoesNotExist() throws Exception {
        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = project(12, 7);
        AgentContext context = AgentContext.create("session", 7, project, "conversation", 1L);
        Files.createDirectories(workspace.resolve("work-4425"));
        Files.writeString(workspace.resolve("work-4425/app.py"), "print(\"ok\")");
        when(projectService.readProjectFile(7, 12, "app.py"))
                .thenThrow(new IllegalArgumentException("path does not exist"));

        JsonObject args = new JsonObject();
        args.addProperty("file_path", "app.py");

        ToolResult result = new ReadFileTool(projectService).execute(context, args);

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("code=FILE_NOT_FOUND"));
        assertTrue(result.getContent().contains("requested_path=app.py"));
        assertTrue(result.getContent().contains("work-4425/app.py"));
        assertTrue(result.getContent().contains("next_action="));
        assertFalse(result.getContent().contains(workspace.toAbsolutePath().toString()));
    }

    @Test
    void returnsFullSha256FingerprintForEditProtocol() throws Exception {
        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = project(12, 7);
        AgentContext context = AgentContext.create("session", 7, project, "conversation", 1L);
        when(projectService.readProjectFile(7, 12, "Main.java")).thenReturn("class Main {}\n");

        JsonObject args = new JsonObject();
        args.addProperty("file_path", "Main.java");

        ToolResult result = new ReadFileTool(projectService).execute(context, args);

        assertTrue(result.isSuccess());
        String header = result.getContent().lines().findFirst().orElseThrow();
        String hash = header.substring(header.indexOf("sha256=") + "sha256=".length(), header.length() - 1);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    private StudentProject project(int projectId, int studentId) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}