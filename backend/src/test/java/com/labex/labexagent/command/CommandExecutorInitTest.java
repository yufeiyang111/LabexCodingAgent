package com.labex.labexagent.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.StudentProject;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.ProjectIndexService;
import com.labex.service.StudentProjectService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommandExecutorInitTest {
    @Test
    void initGeneratesClaudeStyleProjectMemoryDocument() throws Exception {
        Path root = Files.createTempDirectory("labex-init-repo-");
        Files.createDirectories(root.resolve("backend/src/main/resources"));
        Files.createDirectories(root.resolve("frontend/src"));
        Files.writeString(root.resolve("backend/pom.xml"), "<project></project>", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("backend/src/main/resources/application.yml"), "server:\n  port: 8080\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("frontend/package.json"), "{\"scripts\":{\"dev\":\"vite\",\"build\":\"vite build\"},\"dependencies\":{\"vue\":\"^3.4.0\"}}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("frontend/vite.config.js"), "export default {}", StandardCharsets.UTF_8);

        StudentProject project = new StudentProject();
        project.setProjectId(2);
        project.setStudentId(1);
        project.setProjectName("DemoWorkspace");
        project.setWorkspacePath(root.toString());
        project.setStructureJson("{}");

        StudentProjectService projectService = mock(StudentProjectService.class);
        when(projectService.getOwnedProject(1, 2)).thenReturn(project);
        when(projectService.readProjectFile(eq(1), eq(2), eq("LabexAgent.md"))).thenThrow(new IllegalArgumentException("missing"));

        ProjectIndexService indexService = mock(ProjectIndexService.class);
        when(indexService.buildProjectDigest(eq(project), anyString())).thenReturn("""
                Project index (Labex project memory):
                ## Entrypoints
                - backend/pom.xml
                - frontend/package.json
                """);

        CommandExecutor executor = new CommandExecutor(
                new CommandRegistry(),
                projectService,
                mock(AgentConversationService.class),
                indexService,
                null,
                null,
                null);

        CommandExecutor.CommandResult result = executor.execute(1, 2, "init", "prioritize provider docs", null);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(projectService).saveProjectFile(eq(1), eq(2), eq("LabexAgent.md"), contentCaptor.capture());
        String content = contentCaptor.getValue();

        assertThat(result.success()).isTrue();
        assertThat(content)
                .contains("# LabexAgent.md")
                .contains("## Context Contract")
                .contains("Do not preserve noisy facts")
                .contains("## Repository Index")
                .contains("## Build And Verification Index")
                .contains("## Prompt/Context Initialization Rules")
                .contains("## Common Gotchas")
                .contains("cd backend")
                .contains("cd frontend")
                .contains("prioritize provider docs");
    }
}
