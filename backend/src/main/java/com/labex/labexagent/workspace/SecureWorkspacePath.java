package com.labex.labexagent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Resolves user-controlled workspace paths without following links outside the workspace.
 */
public final class SecureWorkspacePath {
    private final Path workspaceRoot;
    private final Path realWorkspaceRoot;

    public SecureWorkspacePath(Path workspaceRoot) {
        try {
            if (workspaceRoot == null) {
                throw new IOException("workspace root is required");
            }
            Path normalized = workspaceRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("workspace root is not a directory");
            }
            verifyRoot(normalized);
            this.workspaceRoot = normalized;
            this.realWorkspaceRoot = normalized.toRealPath();
        } catch (IOException e) {
            throw invalid("workspace root is unavailable", e);
        }
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path resolveExisting(String relativePath) {
        Path target = resolveLexically(relativePath);
        verifyExistingPath(target, true);
        return target;
    }

    public Path resolveForCreate(String relativePath) {
        Path target = resolveLexically(relativePath);
        Path relative = workspaceRoot.relativize(target);
        Path current = workspaceRoot;
        verifyExistingComponent(current);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return target;
            }
            verifyExistingComponent(current);
        }
        return target;
    }

    private Path resolveLexically(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw invalid("workspace-relative path is required", null);
        }
        String normalizedInput = relativePath.trim().replace('\\', '/');
        try {
            Path requested = Path.of(normalizedInput);
            if (requested.isAbsolute() || normalizedInput.startsWith("/") || normalizedInput.matches("^[A-Za-z]:.*")) {
                throw invalid("path must be workspace-relative", null);
            }
            Path target = workspaceRoot.resolve(requested).normalize();
            if (!target.startsWith(workspaceRoot)) {
                throw invalid("path escapes workspace", null);
            }
            return target;
        } catch (InvalidPathException e) {
            throw invalid("path is invalid", e);
        }
    }

    private void verifyExistingPath(Path target, boolean requireTarget) {
        Path relative = workspaceRoot.relativize(target);
        Path current = workspaceRoot;
        verifyExistingComponent(current);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (requireTarget) {
                    throw invalid("path does not exist", null);
                }
                return;
            }
            verifyExistingComponent(current);
        }
    }

    private void verifyExistingComponent(Path path) {
        try {
            verifyExistingComponent(path, workspaceRoot);
        } catch (IOException e) {
            throw invalid("path cannot be inspected", e);
        }
    }

    private void verifyExistingComponent(Path path, Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || Files.isSymbolicLink(path)) {
            throw new IOException("symbolic link or reparse point is not allowed");
        }
        Path realPath = path.toRealPath();
        Path realRoot = root.equals(workspaceRoot) && realWorkspaceRoot != null
                ? realWorkspaceRoot
                : root.toRealPath();
        if (!realPath.startsWith(realRoot)) {
            throw new IOException("path escapes workspace through a link");
        }
    }

    private void verifyRoot(Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || Files.isSymbolicLink(root)) {
            throw new IOException("workspace root cannot be a symbolic link or reparse point");
        }
    }

    private IllegalArgumentException invalid(String message, Exception cause) {
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }
}
