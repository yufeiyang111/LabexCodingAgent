package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.service.StudentProjectService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadFileToolTest {

    @TempDir
    Path workspace;

    @Test
    void returnsFullSha256FingerprintForEditProtocol() throws Exception {
        StudentProjectService projectService = mock(StudentProjectService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
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
}
