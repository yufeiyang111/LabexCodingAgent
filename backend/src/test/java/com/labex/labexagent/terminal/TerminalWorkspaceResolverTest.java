package com.labex.labexagent.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.StudentProject;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalWorkspaceResolverTest {
    @TempDir
    Path tempDir;

    private final StudentProjectService projectService = mock(StudentProjectService.class);
    private final TerminalWorkspaceResolver resolver = new TerminalWorkspaceResolver(projectService);

    @Test
    void resolvesOnlyTheAuthenticatedUsersProjectWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path sourceDirectory = Files.createDirectories(workspace.resolve("src"));
        StudentProject project = project(12, workspace);
        when(projectService.getOwnedProject(7, 12)).thenReturn(project);

        TerminalWorkspaceResolver.TerminalWorkspace terminalWorkspace = resolver.resolveWorkspace(7, 12);

        assertThat(resolver.resolveWorkingDirectory(terminalWorkspace, "src")).isEqualTo(sourceDirectory);
        assertThat(resolver.resolveWorkingDirectory(terminalWorkspace, "")).isEqualTo(workspace);
    }

    @Test
    void rejectsProjectsNotOwnedByTheAuthenticatedUser() {
        when(projectService.getOwnedProject(7, 12)).thenReturn(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolveWorkspace(7, 12))
                .withMessage("Project not found or access denied");
    }

    @Test
    void rejectsAbsoluteAndEscapingWorkingDirectories() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        when(projectService.getOwnedProject(7, 12)).thenReturn(project(12, workspace));
        TerminalWorkspaceResolver.TerminalWorkspace terminalWorkspace = resolver.resolveWorkspace(7, 12);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolveWorkingDirectory(terminalWorkspace, tempDir.toString()))
                .withMessage("cwd must be workspace-relative");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolveWorkingDirectory(terminalWorkspace, "../outside"))
                .withMessage("cwd escapes project workspace");
    }

    @Test
    void rejectsLinkedWorkingDirectories() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = Files.createTempDirectory("labex-terminal-outside");
        Path linkedDirectory = workspace.resolve("linked");
        try {
            Files.createSymbolicLink(linkedDirectory, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links are unavailable in this test environment");
        }
        when(projectService.getOwnedProject(7, 12)).thenReturn(project(12, workspace));
        TerminalWorkspaceResolver.TerminalWorkspace terminalWorkspace = resolver.resolveWorkspace(7, 12);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver.resolveWorkingDirectory(terminalWorkspace, "linked"));
    }

    private StudentProject project(int projectId, Path workspace) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}
