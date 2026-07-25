package com.labex.labexagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolSupportPathTest {

    @TempDir
    Path workspace;

    @Test
    void rejectsAbsolutePathsInsteadOfRewritingThemBelowTheWorkspace() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolSupport.resolve(context(), "/outside.txt"));
    }

    @Test
    void resolvesExistingFilesBelowTheWorkspace() throws Exception {
        Path source = Files.writeString(workspace.resolve("Main.java"), "class Main {}");

        assertEquals(source, ToolSupport.resolve(context(), "Main.java"));
    }

    @Test
    void identifiesOnlyVerifiedExistingWorkspaceEntriesAsSafe() throws Exception {
        Path source = Files.writeString(workspace.resolve("Main.java"), "class Main {}");

        assertTrue(ToolSupport.isSafeExistingWorkspaceEntry(context(), source));
        assertFalse(ToolSupport.isSafeExistingWorkspaceEntry(context(), workspace.getParent()));
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return AgentContext.create("session", 7, project, "conversation", 1L);
    }
}
