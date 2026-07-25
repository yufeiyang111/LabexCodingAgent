package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.labex.entity.StudentProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentContextWorkspacePathTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createRejectsAWorkspacePathThatIsNotADirectory() throws Exception {
        Path workspaceFile = Files.createTempFile(tempDirectory, "workspace", ".txt");
        StudentProject project = new StudentProject();
        project.setWorkspacePath(workspaceFile.toString());

        assertThrows(IllegalArgumentException.class,
                () -> AgentContext.create("session", 7, project, "conversation", 1L));
    }
}
