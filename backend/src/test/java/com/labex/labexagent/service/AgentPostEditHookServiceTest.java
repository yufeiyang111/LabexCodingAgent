package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.entity.AgentRunArtifact;
import com.labex.entity.StudentProject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.mapper.AgentRunArtifactMapper;
import com.labex.mapper.AgentTaskMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AgentPostEditHookServiceTest {

    @TempDir
    Path workspace;

    @Test
    void marksUnavailableLspAsBlockingVerificationRisk() throws Exception {
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
        assertTrue(report.content().contains("status=UNAVAILABLE"));
        assertTrue(report.content().contains("action_required"));
        assertEquals(AgentPostEditHookService.VerificationStatus.UNAVAILABLE, report.status());
        assertFalse(report.unresolvedRisks().isEmpty());
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
        assertEquals(AgentPostEditHookService.VerificationStatus.SKIPPED, report.status());
        verify(lsp, times(0)).diagnostics(any(), any());
    }

    @Test
    void persistsPostEditVerificationWithTheContextExecutionFence() throws Exception {
        Files.writeString(workspace.resolve("File1.java"), "class File1 {}\n");
        LspSessionManager lsp = mock(LspSessionManager.class);
        Diagnostic diagnostic = new Diagnostic(new Range(new Position(0, 0), new Position(0, 5)),
                "boom", DiagnosticSeverity.Error, "lsp:java");
        when(lsp.diagnostics(any(), any())).thenReturn(new LspSessionManager.LspDiagnosticsResult(
                true, "lsp:java", "", List.of(diagnostic)));
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunArtifactMapper artifacts = mock(AgentRunArtifactMapper.class);
        when(artifacts.insert(any())).thenReturn(1);
        AgentPostEditHookService hooks = new AgentPostEditHookService(lsp,
                new AgentRunArtifactService(artifacts, leases));
        ExecutionFence fence = new ExecutionFence(1L, "instance-a", 0L);
        AgentContext context = context();
        context.setExecutionFence(fence);

        AgentPostEditHookService.HookReport report = hooks.afterTool(context, "write_file",
                writeArgs("File1.java"), ToolResult.ok("applied"));

        assertEquals(AgentPostEditHookService.VerificationStatus.FAIL, report.status());
        ArgumentCaptor<AgentRunArtifact> artifactCaptor = ArgumentCaptor.forClass(AgentRunArtifact.class);
        verify(artifacts).insert(artifactCaptor.capture());
        assertEquals("post_edit_verification", artifactCaptor.getValue().getArtifactType());
        assertEquals("post-edit", artifactCaptor.getValue().getArtifactPath());
        assertTrue(artifactCaptor.getValue().getContent().contains("status=FAIL"));
    }

    @Test
    void doesNotPersistPostEditVerificationWithoutAnExecutionFence() throws Exception {
        Files.writeString(workspace.resolve("File1.java"), "class File1 {}\n");
        LspSessionManager lsp = mock(LspSessionManager.class);
        Diagnostic diagnostic = new Diagnostic(new Range(new Position(0, 0), new Position(0, 5)),
                "boom", DiagnosticSeverity.Error, "lsp:java");
        when(lsp.diagnostics(any(), any())).thenReturn(new LspSessionManager.LspDiagnosticsResult(
                true, "lsp:java", "", List.of(diagnostic)));
        AgentRunArtifactMapper artifacts = mock(AgentRunArtifactMapper.class);
        when(artifacts.insert(any())).thenReturn(1);
        AgentPostEditHookService hooks = new AgentPostEditHookService(lsp,
                new AgentRunArtifactService(artifacts, null));

        hooks.afterTool(context(), "write_file", writeArgs("File1.java"), ToolResult.ok("applied"));

        verify(artifacts, never()).insert(any());
    }

    @Test
    void staleFenceSurfacesInsteadOfPartiallyPersistingPostEditEvidence() throws Exception {
        Files.writeString(workspace.resolve("File1.java"), "class File1 {}\n");
        LspSessionManager lsp = mock(LspSessionManager.class);
        Diagnostic diagnostic = new Diagnostic(new Range(new Position(0, 0), new Position(0, 5)),
                "boom", DiagnosticSeverity.Error, "lsp:java");
        when(lsp.diagnostics(any(), any())).thenReturn(new LspSessionManager.LspDiagnosticsResult(
                true, "lsp:java", "", List.of(diagnostic)));
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L);
        AgentRunArtifactMapper artifacts = mock(AgentRunArtifactMapper.class);
        when(artifacts.insert(any())).thenReturn(1);
        AgentPostEditHookService hooks = new AgentPostEditHookService(lsp,
                new AgentRunArtifactService(artifacts, leases));
        AgentContext context = context();
        context.setExecutionFence(new ExecutionFence(1L, "instance-a", 0L));

        assertThatThrownBy(() -> hooks.afterTool(context, "write_file",
                writeArgs("File1.java"), ToolResult.ok("applied")))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);
        verify(artifacts, never()).insert(any());
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return new AgentContext("session", 7, project, "conversation", 1L, workspace,
                List.of(), 0);
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
