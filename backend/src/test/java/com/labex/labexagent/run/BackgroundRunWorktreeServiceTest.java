package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundRunWorktreeServiceTest {
    @TempDir Path tempDir;

    @Test
    void allocatesAnIdempotentManagedWorktreeAndCleansItUp() throws Exception {
        Path source = tempDir.resolve("source"); Files.createDirectories(source);
        git(source, "init"); git(source, "config", "user.email", "test@example.com"); git(source, "config", "user.name", "Test");
        Files.writeString(source.resolve("README.md"), "base"); git(source, "add", "."); git(source, "commit", "-m", "base");
        Path root = tempDir.resolve("workspace"); Files.createDirectories(root.resolve(".labex"));
        git(tempDir, "clone", "--bare", source.toString(), root.resolve(".labex/git").toString());
        BackgroundRunWorktreeService service = new BackgroundRunWorktreeService();
        var first = service.allocate(root, 41); var second = service.allocate(root, 41);
        assertEquals("agent/run-41", first.branch()); assertEquals(first.worktree(), second.worktree()); assertTrue(Files.isRegularFile(first.worktree().resolve("README.md")));
        service.cleanup(root, 41); assertFalse(Files.exists(first.worktree()));
    }

    @Test void rejectsNonPositiveTaskIds() { assertThrows(IllegalArgumentException.class, () -> new BackgroundRunWorktreeService().allocate(tempDir, 0)); }

    private void git(Path directory, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(); command.add("git"); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }
}
