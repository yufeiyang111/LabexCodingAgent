package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentPostEditHookServiceTest {

    @TempDir
    Path workspace;

    @Test
    void capsDiagnosticsAndDoesNotCountUnavailableLspAsAnError() throws Exception {
        for (int index = 1; index <= 4; index++) {
            Files.writeString(workspace.resolve("File" + index + ".java"), "class File" + index + " {}\n");
        }
        LspSessionManager lsp = mock(LspSessionManager.class);
        when(lsp.diagnostics(any(), any())).thenReturn(new LspSessionManager.LspDiagnosticsResult(
                false, "", "not installed", List.of()));
        AgentPostEditHookService hooks = new AgentPostEditHookService(lsp);

        AgentPostEditHookService.HookReport report = hooks.afterTool(context(), "apply_patch", patchArgs(
                "File1.java", "File2.java", "File3.java", "File4.java"), ToolResult.ok("applied"));

        assertEquals(0, report.errorCount());
        assertEquals(0, report.warningCount());
        assertEquals(4, report.changedFiles().size());
        assertTrue(report.content().contains("checked 3, skipped 1, unavailable 3, capped at 3"));
        assertTrue(report.content().contains("lsp unavailable (not installed)"));
        assertFalse(report.content().contains("action_required"));
        verify(lsp, times(3)).diagnostics(any(), any());
    }

    @Test
    void skipsUnsupportedFilesBeforeCallingLsp() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "notes\n");
        LspSessionManager lsp = mock(LspSessionManager.class);
        AgentPostEditHookService hooks = new AgentPostEditHookService(lsp);

        AgentPostEditHookService.HookReport report = hooks.afterTool(context(), "write_file", writeArgs("notes.md"), ToolResult.ok("applied"));

        assertEquals(0, report.errorCount());
        assertTrue(report.content().contains("checked 0, skipped 1, unavailable 0"));
        verify(lsp, times(0)).diagnostics(any(), any());
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return new AgentContext("session", 7, project, "conversation", 1L, workspace,
                List.of(), List.of(), 0);
    }

    private JsonObject writeArgs(String path) {
        JsonObject args = new JsonObject();
        args.addProperty("file_path", path);
        return args;
    }

    private JsonObject patchArgs(String... paths) {
        JsonArray changes = new JsonArray();
        for (String path : paths) {
            JsonObject change = new JsonObject();
            change.addProperty("file_path", path);
            changes.add(change);
        }
        JsonObject args = new JsonObject();
        args.add("changes", changes);
        return args;
    }
}
