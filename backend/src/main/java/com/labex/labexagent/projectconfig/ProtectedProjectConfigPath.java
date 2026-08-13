package com.labex.labexagent.projectconfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * Owns the {@code .labex-agent/project} file boundary inside an owned project workspace.
 *
 * <p>Every read must go through this boundary, which rejects, in order:
 * absolute paths, {@code ..} traversal, lexically escaped paths, symbolic links and reparse
 * points (junctions), real-path escapes, missing files and files over the configured size
 * ceiling. The construction-time snapshot is only a fail-fast check; every {@link #resolve}
 * call re-verifies the whole config directory chain against the current filesystem state, so
 * a link created after construction cannot escape the boundary.
 *
 * <p>All failures are reported as {@link PathViolationException} with a stable reason code
 * from {@link AgentProjectConfigValidator} and the path relative to the config directory.
 */
public final class ProtectedProjectConfigPath {

    /** Config package location relative to the project root. */
    public static final String CONFIG_RELATIVE_DIR = ".labex-agent/project";

    /** Conservative per-file ceiling used when the caller does not configure one. */
    public static final long DEFAULT_MAX_FILE_BYTES = 256L * 1024;

    /** A fail-closed path boundary violation. */
    public static final class PathViolationException extends Exception {
        private final String reasonCode;
        private final String relativePath;

        public PathViolationException(String reasonCode, String relativePath, String message) {
            super(message);
            this.reasonCode = reasonCode;
            this.relativePath = relativePath;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public String relativePath() {
            return relativePath;
        }
    }

    private final Path projectRoot;
    private final Path realProjectRoot;
    private final Path configDir;
    private final long maxFileBytes;

    public ProtectedProjectConfigPath(Path projectRoot) throws PathViolationException {
        this(projectRoot, DEFAULT_MAX_FILE_BYTES);
    }

    public ProtectedProjectConfigPath(Path projectRoot, long maxFileBytes) throws PathViolationException {
        if (projectRoot == null || maxFileBytes <= 0) {
            throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_INVALID, "",
                    "project root and a positive per-file size limit are required");
        }
        String failureReason = AgentProjectConfigValidator.REASON_PATH_INVALID;
        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("project root is not a real directory");
            }
            failureReason = AgentProjectConfigValidator.REASON_PATH_SYMLINK;
            verifyNotLink(root);
            Path realRoot = root.toRealPath();
            Path dir = root.resolve(Path.of(CONFIG_RELATIVE_DIR)).normalize();
            Path current = root;
            for (Path segment : root.relativize(dir)) {
                current = current.resolve(segment);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    verifyNotLink(current);
                }
            }
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                failureReason = AgentProjectConfigValidator.REASON_PATH_OUTSIDE_PROJECT;
                if (!dir.toRealPath().startsWith(realRoot)) {
                    throw new IOException("project config directory escapes the project root");
                }
            }
            this.projectRoot = root;
            this.realProjectRoot = realRoot;
            this.configDir = dir;
            this.maxFileBytes = maxFileBytes;
        } catch (IOException e) {
            throw new PathViolationException(failureReason, "",
                    "project config boundary is unavailable");
        }
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path configDir() {
        return configDir;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    /**
     * Resolves a config-relative path to a real path inside the config directory, rejecting
     * absolute paths, traversal, escapes and symbolic links/reparse points. The config
     * directory chain is re-verified against the current filesystem state on every call, so a
     * link created after this boundary was constructed cannot escape it.
     */
    public Path resolve(String relativePath) throws PathViolationException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_INVALID,
                    String.valueOf(relativePath), "config-relative path is required");
        }
        String input = relativePath.replace('\\', '/');
        try {
            Path requested = Path.of(input);
            if (requested.isAbsolute() || input.startsWith("/") || input.matches("^[A-Za-z]:.*")) {
                throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_ABSOLUTE,
                        relativePath, "path must be relative to the project config directory");
            }
            for (Path segment : requested) {
                if ("..".equals(segment.toString())) {
                    throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_TRAVERSAL,
                            relativePath, "parent directory traversal is not allowed");
                }
            }
            Path target = configDir.resolve(requested).normalize();
            if (!target.startsWith(configDir)) {
                throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_ESCAPE,
                        relativePath, "path escapes the project config directory");
            }
            Path effectiveRealConfigDir = verifyConfigDirChain();
            Path current = configDir;
            for (Path segment : configDir.relativize(target)) {
                current = current.resolve(segment);
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    break;
                }
                verifyComponent(current, effectiveRealConfigDir);
            }
            return target;
        } catch (IOException e) {
            throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_SYMLINK,
                    relativePath, "symbolic link or reparse point is not allowed");
        }
    }

    /** Returns whether the config-relative path exists without following links. */
    public boolean exists(String relativePath) throws PathViolationException {
        return Files.exists(resolve(relativePath), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Reads a config-relative file as UTF-8 after resolving. The file is opened with
     * {@code NOFOLLOW_LINKS} and read in bounded chunks, aborting as soon as the size ceiling
     * is exceeded, so a file that grows or is replaced after the existence check cannot
     * exhaust memory or bypass the boundary.
     */
    public String readText(String relativePath) throws PathViolationException {
        Path target = resolve(relativePath);
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_MISSING,
                        relativePath, "file does not exist");
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream(8192);
            try (SeekableByteChannel channel = Files.newByteChannel(
                    target, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    if ((long) content.size() + buffer.remaining() > maxFileBytes) {
                        throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_SIZE_LIMIT,
                                relativePath, "file exceeds the configured size limit");
                    }
                    content.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
                    buffer.clear();
                }
            } catch (NoSuchFileException e) {
                throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_MISSING,
                        relativePath, "file does not exist");
            }
            return content.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PathViolationException(AgentProjectConfigValidator.REASON_PATH_MISSING,
                    relativePath, "file cannot be read");
        }
    }

    /**
     * Re-verifies every existing component from the project root down to the config directory
     * against the current filesystem state and returns the config directory's current real
     * path, or {@code null} when it does not exist yet.
     */
    private Path verifyConfigDirChain() throws IOException {
        Path current = projectRoot;
        for (Path segment : projectRoot.relativize(configDir)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                verifyNotLink(current);
            }
        }
        if (!Files.exists(configDir, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path real = configDir.toRealPath();
        if (!real.startsWith(realProjectRoot)) {
            throw new IOException("project config directory escapes the project root");
        }
        return real;
    }

    private void verifyComponent(Path current, Path effectiveRealConfigDir) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || Files.isSymbolicLink(current)) {
            throw new IOException("symbolic link or reparse point is not allowed");
        }
        if (effectiveRealConfigDir != null) {
            Path real = current.toRealPath();
            if (!real.startsWith(effectiveRealConfigDir)) {
                throw new IOException("path escapes the project config directory through a link");
            }
        }
    }

    private void verifyNotLink(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || Files.isSymbolicLink(path)) {
            throw new IOException("symbolic link or reparse point is not allowed");
        }
    }
}
