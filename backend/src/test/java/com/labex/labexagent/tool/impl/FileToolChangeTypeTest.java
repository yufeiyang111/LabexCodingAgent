package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileToolChangeTypeTest {

    @TempDir
    Path workspace;

    @Test
    void writeTreatsAnExistingEmptyFileAsAModification() throws Exception {
        Files.writeString(workspace.resolve("Main.java"), "");
        DiffService diffService = mock(DiffService.class);
        PendingChange change = pendingChange();
        when(diffService.stageAndApplyDeferred(any(), any(), any(), any(), eq("Main.java"), eq(""), eq("updated"), eq("modify")))
                .thenReturn(change);
        WriteFileTool tool = new WriteFileTool(diffService, mock(StudentProjectService.class));

        ToolResult result = tool.execute(context(), args("content", "updated"));

        assertTrue(result.isSuccess());
        verify(diffService).stageAndApplyDeferred(any(), any(), any(), any(), eq("Main.java"), eq(""), eq("updated"), eq("modify"));
    }

    @Test
    void editPassesAnExplicitModificationType() throws Exception {
        DiffService diffService = mock(DiffService.class);
        PendingChange change = pendingChange();
        when(diffService.stageAndApplyDeferred(any(), any(), any(), any(), eq("Main.java"), eq("placeholder"), eq("updated"), eq("modify")))
                .thenReturn(change);
        EditFileTool tool = new EditFileTool(diffService, mock(StudentProjectService.class));
        JsonObject args = args("old_string", "placeholder");
        args.addProperty("new_string", "updated");
        Files.writeString(workspace.resolve("Main.java"), "placeholder");

        ToolResult result = tool.execute(context(), args);

        assertTrue(result.isSuccess());
        verify(diffService).stageAndApplyDeferred(any(), any(), any(), any(), eq("Main.java"), eq("placeholder"), eq("updated"), eq("modify"));
    }

    private JsonObject args(String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty("file_path", "Main.java");
        args.addProperty(key, value);
        return args;
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return AgentContext.create("session", 7, project, "conversation", 1L);
    }

    private PendingChange pendingChange() {
        return new PendingChange("change", 7, 12, "conversation", 1L, 99L,
                "Main.java", "modify", "", "updated", "diff", "applied");
    }
}
