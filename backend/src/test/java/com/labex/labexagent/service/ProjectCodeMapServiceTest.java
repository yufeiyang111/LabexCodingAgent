package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.StudentProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectCodeMapServiceTest {

    @TempDir
    Path workspace;

    @Test
    void reusesParsedMetadataUntilContentHashChangesAndEvictsDeletedFiles() throws Exception {
        Path source = Files.createDirectories(workspace.resolve("src")).resolve("App.java");
        Files.writeString(source, "public class App { void start() { } }", StandardCharsets.UTF_8);
        StudentProject project = new StudentProject();
        project.setWorkspacePath(workspace.toString());
        IncrementalContextService index = new IncrementalContextService();
        ProjectCodeMapMetadataCache cache = new ProjectCodeMapMetadataCache();
        ProjectCodeMapService service = new ProjectCodeMapService(index, cache);
        String key = workspace.toAbsolutePath().normalize().toString();

        service.buildRepoMap(project, "start", List.of(), 10);
        ProjectCodeMapMetadataCache.CacheStats initial = cache.stats(key);
        service.buildRepoMap(project, "start", List.of(), 10);
        ProjectCodeMapMetadataCache.CacheStats unchanged = cache.stats(key);
        Files.writeString(source, "\n// changed", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        service.buildRepoMap(project, "start", List.of(), 10);
        ProjectCodeMapMetadataCache.CacheStats changed = cache.stats(key);
        Files.delete(source);
        service.buildRepoMap(project, "start", List.of(), 10);

        assertEquals(1, initial.entries());
        assertEquals(1, initial.misses());
        assertEquals(1, unchanged.hits());
        assertEquals(2, changed.misses());
        assertEquals(0, cache.stats(key).entries());
    }

    @Test
    void mapsProjectFilesAfterLargeIgnoredDependencyDirectory() throws Exception {
        Path dependencies = Files.createDirectories(workspace.resolve("node_modules"));
        for (int index = 0; index < 3_001; index++) {
            Files.writeString(dependencies.resolve("dependency-" + index + ".js"), "export default {}", StandardCharsets.UTF_8);
        }
        Path source = Files.createDirectories(workspace.resolve("src")).resolve("main.js");
        Files.writeString(source, "export const start = () => 'ready'", StandardCharsets.UTF_8);

        StudentProject project = new StudentProject();
        project.setWorkspacePath(workspace.toString());

        String repoMap = new ProjectCodeMapService().buildRepoMap(project, "start", List.of(), 10);

        assertTrue(repoMap.contains("src/main.js"),
                "Ignored dependency files must not consume the repository map scan limit");
    }
}
