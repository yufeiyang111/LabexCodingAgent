package com.labex.labexagent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureWorkspacePathTest {

    @TempDir
    Path workspace;

    @Test
    void resolvesExistingFilesOnlyInsideTheWorkspace() throws Exception {
        Path source = Files.createDirectories(workspace.resolve("src"));
        Path file = Files.writeString(source.resolve("Main.java"), "class Main {}");
        SecureWorkspacePath paths = new SecureWorkspacePath(workspace);

        assertEquals(file, paths.resolveExisting("src/Main.java"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveExisting("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveExisting(workspace.getParent().toString()));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveExisting("C:/outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveExisting("missing.txt"));
    }

    @Test
    void resolvesNewFilesOnlyBelowAnExistingNonLinkedAncestor() throws Exception {
        Path source = Files.createDirectories(workspace.resolve("src"));
        SecureWorkspacePath paths = new SecureWorkspacePath(workspace);

        assertEquals(source.resolve("new/Example.java"), paths.resolveForCreate("src/new/Example.java"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveForCreate("../outside.txt"));
    }

    @Test
    void rejectsSymbolicLinksForExistingAndCreatedPaths() throws Exception {
        Path outside = Files.createTempDirectory("labex-secure-path-outside");
        Path link = workspace.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links are unavailable in this test environment");
        }
        SecureWorkspacePath paths = new SecureWorkspacePath(workspace);

        assertThrows(IllegalArgumentException.class, () -> paths.resolveExisting("linked"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveForCreate("linked/new.txt"));
    }
}
