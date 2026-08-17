package com.labex.labexagent.projectconfig;

import com.labex.labexagent.projectconfig.ProtectedProjectConfigPath.PathViolationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Controlled writer for the protected project configuration tree
 * ({@code .labex-agent/project/**}).
 *
 * <p>Only paths validated by {@link ProtectedProjectConfigPath} beneath the protected tree
 * are writable; traversal, absolute, escaping and link-violating paths fail closed with
 * {@link PathViolationException} before anything is created. The caller must pass the root of
 * an already-<em>owned</em> project (obtained through
 * {@link AgentProjectConfigOwnership#requireOwned}): this class is a filesystem primitive and
 * deliberately takes no owner identity; ownership is enforced by every service boundary that
 * invokes it.
 *
 * <p>Each write is serialized by a lock file at the project root, written to a temporary file
 * in the target directory, moved atomically (or fail-closed replaced when atomic moves are
 * unsupported) and verified by read-back; any failure removes the temporary file and leaves
 * the previous content untouched.
 *
 * <p>Durability contract: the temporary file's CONTENT is force-fsynced
 * ({@link FileChannel#force}) before the move, so the bytes are durable once the move
 * completes. No directory fsync is performed — it is not portable across the supported
 * filesystems — so the rename itself is not guaranteed to survive a power failure and
 * durability of the rename relies on the operating system. Post-write verification catches
 * read-back mismatches and fails closed.
 */
@Component
public class AgentProjectConfigFileWriter {

    /** Lock file location is outside the protected tree so it never pollutes the tree digest. */
    public static final String LOCK_RELATIVE_PATH = ".labex-agent/.config-writer.lock";

    /**
     * Executes the operation while holding the project-root write lock. The lock is reentrant
     * for the owning thread, so apply steps that span multiple writer primitives can hold it
     * across {@code publishStaged}/{@code delete} without deadlocking.
     */
    public <T> T withProjectLock(Path projectRoot, ProjectLockedOperation<T> operation)
            throws IOException, PathViolationException {
        Path lockPath = projectRoot.resolve(LOCK_RELATIVE_PATH).normalize();
        Files.createDirectories(lockPath.getParent());
        LockHandle handle = acquireLock(lockPath);
        try {
            return operation.execute();
        } finally {
            handle.close();
        }
    }

    /** Lock-scoped operation over the project configuration tree. */
    @FunctionalInterface
    public interface ProjectLockedOperation<T> {
        T execute() throws IOException, PathViolationException;
    }

    /** Staging location for an in-flight proposal apply, outside the protected tree. */
    public static final String STAGING_RELATIVE_DIR = ".labex-agent/.config-staging";

    /** Default wait budget for the project-root write lock. */
    public static final long DEFAULT_LOCK_TIMEOUT_MILLIS = 10_000;

    private static final long LOCK_POLL_MILLIS = 20;

    private static final Pattern SAFE_STAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final long lockTimeoutMillis;

    public AgentProjectConfigFileWriter() {
        this(DEFAULT_LOCK_TIMEOUT_MILLIS);
    }

    public AgentProjectConfigFileWriter(long lockTimeoutMillis) {
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    /**
     * Atomically writes or replaces one validated file beneath {@code .labex-agent/project/}.
     *
     * @param projectRoot  root of an already-owned project workspace
     * @param relativePath config-relative path beneath the protected tree
     * @param content      UTF-8 content to write
     * @throws PathViolationException when the path is not inside the protected tree
     * @throws IOException            on lock, write, move or verification failure
     */
    public void write(Path projectRoot, String relativePath, String content)
            throws IOException, PathViolationException {
        ProtectedProjectConfigPath boundary = new ProtectedProjectConfigPath(projectRoot);
        Path target = boundary.resolve(relativePath);
        Path lockPath = projectRoot.resolve(LOCK_RELATIVE_PATH).normalize();
        Files.createDirectories(lockPath.getParent());
        ensureParentDirectories(boundary, target);

        LockHandle handle = acquireLock(lockPath);
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(),
                    "." + target.getFileName() + ".tmp-", ".tmp");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            replace(temp, boundary.resolve(relativePath));
            temp = null;
            String verified = boundary.readText(relativePath);
            if (!content.equals(verified)) {
                throw new IOException("post-write verification failed for " + relativePath);
            }
        } catch (IOException | PathViolationException failure) {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
            throw failure;
        } finally {
            handle.close();
        }
    }

    /**
     * Writes one candidate file into the proposal's staging directory
     * ({@code .labex-agent/.config-staging/<stageId>/}) with the same atomic write discipline
     * as {@link #write}: the file is force-fsynced before an atomic move and verified by
     * read-back. The relative path is validated against the protected-tree boundary first, so
     * traversal, absolute, escaping and link-violating candidate paths fail closed before
     * anything is created. Staging files stay in place until {@link #cleanupStaging}; the
     * apply publishes them through {@link #publishStaged}.
     *
     * @param stageId      deterministic staging key derived from the proposal idempotency key
     * @param relativePath config-relative candidate path
     * @param content      UTF-8 candidate file content
     */
    public void stage(Path projectRoot, String stageId, String relativePath, String content)
            throws IOException, PathViolationException {
        Path stageDir = stagingDir(projectRoot, stageId);
        validateConfigRelativePath(projectRoot, relativePath);
        Path target = stageDir.resolve(Path.of(relativePath.replace('\\', '/'))).normalize();
        if (!target.startsWith(stageDir)) {
            throw new IOException("staging path escapes the staging directory: " + relativePath);
        }
        Path lockPath = projectRoot.resolve(LOCK_RELATIVE_PATH).normalize();
        Files.createDirectories(lockPath.getParent());
        Files.createDirectories(target.getParent());

        LockHandle handle = acquireLock(lockPath);
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(),
                    "." + target.getFileName() + ".tmp-", ".tmp");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            replace(temp, target);
            temp = null;
            String verified = Files.readString(target, StandardCharsets.UTF_8);
            if (!content.equals(verified)) {
                throw new IOException("post-write verification failed for staged " + relativePath);
            }
        } catch (IOException failure) {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
            throw failure;
        } finally {
            handle.close();
        }
    }

    /**
     * Copies every staged candidate file into the protected tree (temp + fsync + atomic move)
     * and verifies each file by read-back. The staged files are intentionally kept until
     * {@link #cleanupStaging}, so a crash mid-publish can be healed by re-publishing the same
     * approved candidate. Published content must be byte-identical to the staged candidate.
     */
    public void publishStaged(Path projectRoot, String stageId, List<String> relativePaths)
            throws IOException, PathViolationException {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return;
        }
        Path stageDir = stagingDir(projectRoot, stageId);
        Path lockPath = projectRoot.resolve(LOCK_RELATIVE_PATH).normalize();
        Files.createDirectories(lockPath.getParent());
        LockHandle handle = acquireLock(lockPath);
        try {
            for (String relativePath : relativePaths) {
                ProtectedProjectConfigPath boundary = new ProtectedProjectConfigPath(projectRoot);
                Path target = boundary.resolve(relativePath);
                ensureParentDirectories(boundary, target);
                Path staged = stageDir.resolve(Path.of(relativePath.replace('\\', '/'))).normalize();
                if (!staged.startsWith(stageDir) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("staged file is missing for " + relativePath);
                }
                copyVerified(staged, target, boundary, relativePath);
            }
        } finally {
            handle.close();
        }
    }

    private void copyVerified(Path staged, Path target, ProtectedProjectConfigPath boundary,
                              String relativePath) throws IOException, PathViolationException {
        Path temp = null;
        try {
            byte[] bytes = Files.readAllBytes(staged);
            temp = Files.createTempFile(target.getParent(),
                    "." + target.getFileName() + ".tmp-", ".tmp");
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            replace(temp, target);
            temp = null;
            String expected = Files.readString(staged, StandardCharsets.UTF_8);
            String verified = boundary.readText(relativePath);
            if (!expected.equals(verified)) {
                throw new IOException("post-publish verification failed for " + relativePath);
            }
        } finally {
            if (temp != null) {
                Files.deleteIfExists(temp);
            }
        }
    }

    /**
     * Deletes one validated protected-tree file under the project-root lock. Used by the apply
     * to remove files that are part of the accepted head but absent from the approved
     * complete candidate tree.
     */
    public void delete(Path projectRoot, String relativePath) throws IOException, PathViolationException {
        ProtectedProjectConfigPath boundary = new ProtectedProjectConfigPath(projectRoot);
        Path target = boundary.resolve(relativePath);
        Path lockPath = projectRoot.resolve(LOCK_RELATIVE_PATH).normalize();
        Files.createDirectories(lockPath.getParent());
        LockHandle handle = acquireLock(lockPath);
        try {
            Files.deleteIfExists(target);
        } finally {
            handle.close();
        }
    }

    /** Removes the proposal's staging directory after the apply reaches a terminal state. */
    public void cleanupStaging(Path projectRoot, String stageId) throws IOException {
        Path stageDir = stagingDir(projectRoot, stageId);
        if (!Files.exists(stageDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var stream = Files.walk(stageDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private Path stagingDir(Path projectRoot, String stageId) throws IOException {
        if (stageId == null || !SAFE_STAGE_ID.matcher(stageId).matches()) {
            throw new IOException("invalid staging id");
        }
        return projectRoot.resolve(STAGING_RELATIVE_DIR).resolve(stageId).normalize();
    }

    private void validateConfigRelativePath(Path projectRoot, String relativePath)
            throws IOException, PathViolationException {
        new ProtectedProjectConfigPath(projectRoot).resolve(relativePath);
    }

    private void ensureParentDirectories(ProtectedProjectConfigPath boundary, Path target)
            throws IOException, PathViolationException {
        Path configDir = boundary.configDir();
        Path relative = configDir.relativize(target).getParent();
        if (relative == null) {
            return;
        }
        Path current = configDir;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Files.createDirectory(current);
        }
    }

    private void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private LockHandle acquireLock(Path lockPath) throws IOException {
        java.util.ArrayDeque<Path> held = HELD_LOCK_PATHS.get();
        if (held.contains(lockPath)) {
            held.push(lockPath);
            return new LockHandle(null, null);
        }
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + lockTimeoutMillis * 1_000_000L;
        try {
            while (true) {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException heldByThisJvm) {
                    lock = null;
                }
                if (lock != null) {
                    held.push(lockPath);
                    return new LockHandle(lock, channel);
                }
                if (System.nanoTime() >= deadline) {
                    channel.close();
                    throw new IOException("timed out acquiring the project config writer lock");
                }
                try {
                    Thread.sleep(LOCK_POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    channel.close();
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while acquiring the project config writer lock", interrupted);
                }
            }
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    /** JVM-local reentrancy markers; OS file locks are not reentrant, so nested lock scopes
     * on the same thread must not acquire a second OS lock. */
    private static final ThreadLocal<java.util.ArrayDeque<Path>> HELD_LOCK_PATHS =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private static final class LockHandle implements AutoCloseable {
        private final FileLock lock;
        private final FileChannel channel;

        private LockHandle(FileLock lock, FileChannel channel) {
            this.lock = lock;
            this.channel = channel;
        }

        @Override
        public void close() throws IOException {
            java.util.ArrayDeque<Path> held = HELD_LOCK_PATHS.get();
            if (lock == null) {
                if (!held.isEmpty()) {
                    held.pop();
                }
                return;
            }
            try {
                lock.release();
            } finally {
                channel.close();
            }
            if (!held.isEmpty()) {
                held.pop();
            }
        }
    }
}
