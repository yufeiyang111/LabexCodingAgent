package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.StudentProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIndexServiceTest {

    @TempDir
    Path workspace;

    @Test
    void indexesProjectFilesAfterLargeIgnoredDependencyDirectory() throws Exception {
        Path dependencies = Files.createDirectories(workspace.resolve("node_modules"));
        for (int index = 0; index < 2_701; index++) {
            Files.writeString(dependencies.resolve("dependency-" + index + ".js"), "export default {}", StandardCharsets.UTF_8);
        }
        Path source = Files.createDirectories(workspace.resolve("src")).resolve("main.js");
        Files.writeString(source, "export const start = () => 'ready'", StandardCharsets.UTF_8);

        StudentProject project = new StudentProject();
        project.setWorkspacePath(workspace.toString());

        String digest = new ProjectIndexService().buildProjectDigest(project, "start");

        assertTrue(digest.contains("src/main.js"),
                "Ignored dependency files must not consume the project index scan limit");
    }
}
