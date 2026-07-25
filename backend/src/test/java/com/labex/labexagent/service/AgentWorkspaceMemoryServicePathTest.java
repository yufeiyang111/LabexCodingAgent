package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentWorkspaceMemoryServicePathTest {

    @TempDir
    Path workspace;

    @Test
    void doesNotWriteMemoryThroughALinkedLabexDirectory() throws Exception {
        Path outside = Files.createTempDirectory("labex-agent-memory-outside");
        try {
            Files.createSymbolicLink(workspace.resolve(".labex"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links are unavailable in this test environment");
        }
        StudentProject project = new StudentProject();
        project.setWorkspacePath(workspace.toString());
        AgentContext context = AgentContext.create("session", 7, project, "conversation", 1L);

        new AgentWorkspaceMemoryService().recordDecision(context, "decision", "do not escape");

        assertFalse(Files.exists(outside.resolve("agent-memory.json")));
    }
}
