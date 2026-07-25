package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundRunWorkspaceResolverTest {
    @TempDir Path root;

    @Test void resolvesOnlyAnExistingManagedWorktree() throws Exception {
        Path worktree = Files.createDirectories(root.resolve(".labex/background-runs/12"));
        assertEquals(worktree.toAbsolutePath().normalize(), BackgroundRunWorkspaceResolver.resolve(root, worktree.toString()));
        assertNull(BackgroundRunWorkspaceResolver.resolve(root, null));
    }

    @Test void rejectsMissingOrEscapingPaths() throws Exception {
        Path escaping = Files.createDirectories(root.resolveSibling("outside-worktree"));
        assertThrows(IllegalStateException.class, () -> BackgroundRunWorkspaceResolver.resolve(root, escaping.toString()));
        assertThrows(IllegalStateException.class, () -> BackgroundRunWorkspaceResolver.resolve(root, root.resolve(".labex/background-runs/missing").toString()));
    }
}
