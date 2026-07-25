package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.StudentProject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.runtime.AgentContextManager;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentContextOrchestratorIndexReuseTest {

    @Test
    void reusesOneIndexSnapshotAcrossTheInitialContextBuilders() {
        AgentContextManager contextManager = mock(AgentContextManager.class);
        ProjectIndexService projectIndexService = mock(ProjectIndexService.class);
        AgentWorkspaceMemoryService workspaceMemoryService = mock(AgentWorkspaceMemoryService.class);
        ProjectCodeMapService projectCodeMapService = mock(ProjectCodeMapService.class);
        LspSessionManager lspSessionManager = mock(LspSessionManager.class);
        AgentContextOrchestrator orchestrator = new AgentContextOrchestrator(
                contextManager, projectIndexService, workspaceMemoryService, lspSessionManager, projectCodeMapService);

        StudentProject project = new StudentProject();
        project.setProjectName("SnapshotWorkspace");
        project.setWorkspacePath("D:/workspaces/snapshot");
        IncrementalContextService.IndexSnapshot snapshot = new IncrementalContextService.IndexSnapshot(
                List.of(), new IncrementalContextService.IndexStats(0, 0, 0, 0, 0),
                new IncrementalContextService.IndexScanStatus(0, 0, 0, 0, "COMPLETED", 0));
        AgentWorkspaceMemoryService.WorkspaceMemory memory = new AgentWorkspaceMemoryService.WorkspaceMemory();

        when(projectIndexService.snapshot(project)).thenReturn(snapshot);
        when(contextManager.buildInitialContext(eq(project), isNull(), isNull(), eq("tools"), eq("question"),
                eq(""), eq(false), same(snapshot))).thenReturn("base");
        when(workspaceMemoryService.readMemory(project)).thenReturn(memory);
        when(projectIndexService.buildAdaptiveProjectContext(eq(project), eq("question"), same(memory.touchedFiles),
                same(snapshot))).thenReturn("adaptive");
        when(projectCodeMapService.buildRepoMap(eq(project), eq("question"), same(memory.touchedFiles), eq(20),
                same(snapshot))).thenReturn("repo map");
        when(workspaceMemoryService.buildMemoryContext(project, "question", null)).thenReturn("memory");

        AgentContextOrchestrator.ContextBundle bundle = orchestrator.buildInitialBundle(
                project, null, null, "tools", "question", "", false, null);

        assertThat(bundle.content()).contains("base", "adaptive", "repo map", "memory");
        verify(projectIndexService).snapshot(project);
        verify(contextManager).buildInitialContext(eq(project), isNull(), isNull(), eq("tools"), eq("question"),
                eq(""), eq(false), same(snapshot));
        verify(projectIndexService).buildAdaptiveProjectContext(eq(project), eq("question"), same(memory.touchedFiles),
                same(snapshot));
        verify(projectCodeMapService).buildRepoMap(eq(project), eq("question"), same(memory.touchedFiles), eq(20),
                same(snapshot));
    }
}
