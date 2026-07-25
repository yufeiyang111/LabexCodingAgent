package com.labex.labexagent.run;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class BackgroundRunWorkspaceResolver {
    private BackgroundRunWorkspaceResolver() { }

    public static Path resolve(Path projectRoot, String persistedWorktree) {
        if (persistedWorktree == null || persistedWorktree.isBlank()) return null;
        Path root = projectRoot.toAbsolutePath().normalize();
        Path managedRoot = root.resolve(".labex").resolve("background-runs").normalize();
        Path worktree = Path.of(persistedWorktree).toAbsolutePath().normalize();
        if (!worktree.startsWith(managedRoot) || !Files.isDirectory(worktree, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("background worktree is unavailable or escapes its managed directory");
        }
        return worktree;
    }
}
