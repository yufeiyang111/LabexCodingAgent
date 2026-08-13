package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.labex.labexagent.projectconfig.ProtectedProjectConfigPath.PathViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProjectConfigFileWriterTest {

    @TempDir
    Path root;

    private Path configDir(Path project) {
        return project.resolve(".labex-agent").resolve("project");
    }

    private Path writeValidTree(String name) throws Exception {
        Path project = root.resolve(name);
        Path config = configDir(project);
        Files.createDirectories(config.resolve("agents"));
        Files.createDirectories(config.resolve("tools"));
        Files.writeString(config.resolve("agent.json"), "{\"schemaVersion\":1}");
        return project;
    }

    private List<Path> tempLeftovers(Path dir) throws Exception {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> path.getFileName().toString().contains(".tmp-")).toList();
        }
    }

    @Test
    void writesNewValidatedFileBeneathTheProtectedTree() throws Exception {
        Path project = writeValidTree("new-file");

        new AgentProjectConfigFileWriter().write(project, "agents/new.json", "{\"id\":\"new\"}");

        Path written = configDir(project).resolve("agents/new.json");
        assertThat(Files.isRegularFile(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo("{\"id\":\"new\"}");
        assertThat(tempLeftovers(configDir(project))).isEmpty();
    }

    @Test
    void createsMissingParentDirectoriesForValidatedPaths() throws Exception {
        Path project = writeValidTree("nested");

        new AgentProjectConfigFileWriter().write(project, "skills/backend.md", "# Backend");

        assertThat(Files.readString(configDir(project).resolve("skills/backend.md"))).isEqualTo("# Backend");
    }

    @Test
    void overwritesExistingFileWithoutPartialContentOrTempLeftovers() throws Exception {
        Path project = writeValidTree("overwrite");
        AgentProjectConfigFileWriter writer = new AgentProjectConfigFileWriter();
        writer.write(project, "agents/main.json", "{\"id\":\"main\",\"name\":\"First\"}");

        writer.write(project, "agents/main.json", "{\"id\":\"main\",\"name\":\"Second\"}");

        assertThat(Files.readString(configDir(project).resolve("agents/main.json")))
                .isEqualTo("{\"id\":\"main\",\"name\":\"Second\"}");
        assertThat(tempLeftovers(configDir(project))).isEmpty();
        assertThat(tempLeftovers(project.resolve(".labex-agent"))).isEmpty();
    }

    @Test
    void rejectsTraversalAbsoluteAndEscapingPathsWithoutCreatingAnything() throws Exception {
        Path project = writeValidTree("reject");
        AgentProjectConfigFileWriter writer = new AgentProjectConfigFileWriter();

        assertThatThrownBy(() -> writer.write(project, "../escape.json", "{}"))
                .isInstanceOf(PathViolationException.class);
        assertThatThrownBy(() -> writer.write(project, "agents/../../escape.json", "{}"))
                .isInstanceOf(PathViolationException.class);
        assertThatThrownBy(() -> writer.write(project, "C:/evil.json", "{}"))
                .isInstanceOf(PathViolationException.class);
        assertThatThrownBy(() -> writer.write(project, "/etc/evil.json", "{}"))
                .isInstanceOf(PathViolationException.class);
        assertThatThrownBy(() -> writer.write(project, "..\\windows\\evil.json", "{}"))
                .isInstanceOf(PathViolationException.class);

        assertThat(Files.exists(root.resolve("escape.json"))).isFalse();
        assertThat(tempLeftovers(configDir(project))).isEmpty();
    }

    @Test
    void cleansUpTempFileWhenReplacementFailsClosed() throws Exception {
        Path project = writeValidTree("fail-closed");
        AgentProjectConfigFileWriter writer = new AgentProjectConfigFileWriter();

        assertThatThrownBy(() -> writer.write(project, "agents", "{\"cannot\":\"replace a directory\"}"))
                .isInstanceOf(Exception.class);

        assertThat(Files.isDirectory(configDir(project).resolve("agents"))).isTrue();
        assertThat(Files.readString(configDir(project).resolve("agent.json"))).isEqualTo("{\"schemaVersion\":1}");
        assertThat(tempLeftovers(configDir(project))).isEmpty();
    }

    @Test
    void writeTimesOutWhenTheProjectRootLockIsHeldByAnotherOwner() throws Exception {
        Path project = writeValidTree("locked");
        Path lockPath = project.resolve(".labex-agent").resolve(".config-writer.lock");
        Files.createDirectories(lockPath.getParent());
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lockPath,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock held = channel.lock()) {
            AgentProjectConfigFileWriter writer = new AgentProjectConfigFileWriter(100);

            assertThatThrownBy(() -> writer.write(project, "agents/new.json", "{}"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("timed out acquiring the project config writer lock");
        }
        assertThat(Files.exists(configDir(project).resolve("agents/new.json"))).isFalse();
        assertThat(tempLeftovers(configDir(project))).isEmpty();
    }

    @Test
    void serializesConcurrentWritesUnderTheProjectRootLock() throws Exception {
        Path project = writeValidTree("concurrent");
        AgentProjectConfigFileWriter writer = new AgentProjectConfigFileWriter();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                final int fileIndex = index;
                futures.add(executor.submit(() -> {
                    try {
                        writer.write(project, "tools/tool-" + fileIndex + ".json",
                                "{\"name\":\"tool-" + fileIndex + "\"}");
                        return null;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        for (int index = 0; index < 8; index++) {
            Path written = configDir(project).resolve("tools/tool-" + index + ".json");
            assertThat(Files.readString(written)).isEqualTo("{\"name\":\"tool-" + index + "\"}");
        }
        assertThat(tempLeftovers(configDir(project))).isEmpty();
    }
}
