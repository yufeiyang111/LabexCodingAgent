package com.labex.labexagent.diff;

import com.labex.entity.StudentProject;
import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GitSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(GitSnapshotService.class);
    private static final int MAX_OUTPUT = 120_000;
    private static final int SNAPSHOT_TIMEOUT_SECONDS = 20;

    /**
     * Captures every trackable workspace file. This remains the compatibility entry point for
     * tools whose writes cannot be reduced to a verified path list (for example shell commands).
     */
    public Snapshot capture(StudentProject project, String label) {
        return captureInternal(project, label, List.of(), false);
    }

    /**
     * Captures only the supplied, workspace-relative paths. The private Git index is updated with
     * those paths and {@code git write-tree} returns a tree object; no commit, status scan, or
     * HEAD lookup is needed on the write-tool hot path.
     */
    public Snapshot capture(StudentProject project, String label, Collection<String> relativePaths) {
        List<String> paths = trackablePaths(relativePaths);
        if (paths.isEmpty()) {
            return Snapshot.unavailable("no trackable paths");
        }
        return captureInternal(project, label, paths, true);
    }

    private Snapshot captureInternal(StudentProject project, String label, List<String> paths, boolean pathScoped) {
        long startedNanos = System.nanoTime();
        Integer projectId = project == null ? null : project.getProjectId();
        String scope = pathScoped ? "paths" : "global";
        String safeLabel = safeLabel(label);
        log.info("GIT_SNAPSHOT_CAPTURE_START projectId={} label={} scope={} pathCount={}",
                projectId, safeLabel, scope, paths.size());
        try {
            Path root = workspaceRoot(project);
            if (!Files.isDirectory(root)) {
                Snapshot unavailable = Snapshot.unavailable("workspace not found");
                log.warn("GIT_SNAPSHOT_CAPTURE_UNAVAILABLE projectId={} label={} scope={} elapsedMs={} reason={}",
                        projectId, safeLabel, scope, elapsedMs(startedNanos), unavailable.error());
                return unavailable;
            }
            Path gitDir = gitDir(root);
            init(root, gitDir);
            Path captureIndex = pathScoped ? createEphemeralIndex(gitDir) : null;
            try {
                long stageStartedNanos = System.nanoTime();
                if (pathScoped) {
                    runGit(root, gitDir, SNAPSHOT_TIMEOUT_SECONDS, pathScopedAddArgs(paths), captureIndex);
                } else {
                    runGit(root, gitDir, SNAPSHOT_TIMEOUT_SECONDS,
                            buildArgs("add", "-A", "--", ".", excludedPathspecs()));
                }
                long stageElapsedMs = elapsedMs(stageStartedNanos);

                long writeTreeStartedNanos = System.nanoTime();
                String ref = runGit(root, gitDir, SNAPSHOT_TIMEOUT_SECONDS, List.of("write-tree"), captureIndex).output().trim();
            if (ref.isBlank()) {
                throw new IOException("git write-tree returned an empty tree ref");
            }
                long writeTreeElapsedMs = elapsedMs(writeTreeStartedNanos);
                Snapshot snapshot = Snapshot.available(ref, "tree");
                log.info("GIT_SNAPSHOT_CAPTURE_COMPLETE projectId={} label={} scope={} pathCount={} state={} refPrefix={} stageMs={} writeTreeMs={} elapsedMs={} indexMode={}",
                        projectId, safeLabel, scope, paths.size(), snapshot.status(), ref.substring(0, Math.min(12, ref.length())),
                        stageElapsedMs, writeTreeElapsedMs, elapsedMs(startedNanos),
                        pathScoped ? "ephemeral" : "shared");
                return snapshot;
            } finally {
                if (captureIndex != null) {
                    try {
                        Files.deleteIfExists(captureIndex);
                    } catch (IOException cleanupFailure) {
                        log.debug("Unable to remove ephemeral Git snapshot index: {}", cleanupFailure.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("GIT_SNAPSHOT_CAPTURE_FAILED projectId={} label={} scope={} pathCount={} elapsedMs={} errorType={} error={}",
                    projectId, safeLabel, scope, paths.size(), elapsedMs(startedNanos),
                    e.getClass().getSimpleName(), e.getMessage());
            return Snapshot.unavailable(e.getMessage());
        }
    }

    public List<ChangedFile> changedFiles(StudentProject project, Snapshot before, Snapshot after) {
        if (!usablePair(before, after)) {
            return List.of();
        }
        try {
            Path root = workspaceRoot(project);
            Path gitDir = gitDir(root);
            String output = runGit(root, gitDir, 60, List.of("diff", "--name-status", "-M", before.ref(), after.ref(), "--")).output();
            List<ChangedFile> files = new ArrayList<>();
            for (String line : output.split("\\R")) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\t");
                if (parts.length < 2) {
                    continue;
                }
                String status = parts[0];
                String oldPath = parts.length > 2 ? normalizeGitPath(parts[1]) : "";
                String path = normalizeGitPath(parts.length > 2 ? parts[2] : parts[1]);
                if (!path.isBlank() && isTrackable(path)) {
                    files.add(new ChangedFile(status, oldPath, path));
                }
            }
            return files;
        } catch (Exception e) {
            log.debug("Unable to read snapshot diff: {}", e.getMessage());
            return List.of();
        }
    }

    public String diffForFile(StudentProject project, Snapshot before, Snapshot after, ChangedFile file) {
        if (!usablePair(before, after) || file == null) {
            return "";
        }
        try {
            Path root = workspaceRoot(project);
            Path gitDir = gitDir(root);
            List<String> args = new ArrayList<>();
            args.add("diff");
            args.add("-M");
            args.add("--binary");
            args.add(before.ref());
            args.add(after.ref());
            args.add("--");
            if (file.oldPath() != null && !file.oldPath().isBlank()) {
                args.add(file.oldPath());
            }
            args.add(file.path());
            return limit(runGit(root, gitDir, 60, args).output(), MAX_OUTPUT);
        } catch (Exception e) {
            return "";
        }
    }

    public String readTextAt(StudentProject project, String ref, String path) {
        if (ref == null || ref.isBlank() || !isTrackable(path)) {
            return "";
        }
        try {
            Path root = workspaceRoot(project);
            Path gitDir = gitDir(root);
            CommandResult result = runGit(root, gitDir, 30, List.of("show", ref + ":" + path));
            return limit(result.output(), MAX_OUTPUT);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean restore(StudentProject project, String beforeRef, String serializedPaths) {
        if (beforeRef == null || beforeRef.isBlank() || serializedPaths == null || serializedPaths.isBlank()) {
            return false;
        }
        try {
            Path root = workspaceRoot(project);
            Path gitDir = gitDir(root);
            List<ChangedFile> files = deserializePaths(serializedPaths);
            if (files.isEmpty()) {
                return false;
            }
            for (ChangedFile file : files) {
                restoreOne(root, gitDir, beforeRef, file);
            }
            capture(project, "undo " + beforeRef.substring(0, Math.min(8, beforeRef.length())));
            return true;
        } catch (Exception e) {
            log.warn("Snapshot restore failed: {}", e.getMessage());
            return false;
        }
    }

    public String serializePaths(List<ChangedFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ChangedFile file : files) {
            if (file == null) {
                continue;
            }
            builder.append(encode(file.status())).append('\t')
                    .append(encode(file.oldPath())).append('\t')
                    .append(encode(file.path())).append('\n');
        }
        return builder.toString();
    }

    public List<ChangedFile> deserializePaths(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ChangedFile> files = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
            if (parts.length < 3) {
                continue;
            }
            String status = decode(parts[0]);
            String oldPath = decode(parts[1]);
            String path = decode(parts[2]);
            if (!path.isBlank() && isTrackable(path)) {
                files.add(new ChangedFile(status, oldPath, path));
            }
        }
        return files;
    }

    public boolean usablePair(Snapshot before, Snapshot after) {
        return before != null && after != null && before.available() && after.available()
                && before.ref() != null && after.ref() != null
                && !before.ref().isBlank() && !after.ref().isBlank()
                && !before.ref().equals(after.ref());
    }

    private void restoreOne(Path root, Path gitDir, String beforeRef, ChangedFile file) throws IOException, InterruptedException {
        String status = file.status() == null ? "" : file.status().toUpperCase(Locale.ROOT);
        String restorePath = file.oldPath() != null && !file.oldPath().isBlank() && status.startsWith("R")
                ? file.oldPath()
                : file.path();
        if (status.startsWith("R")) {
            deletePath(root, file.path());
        }
        if (existsAt(root, gitDir, beforeRef, restorePath)) {
            runGit(root, gitDir, 60, List.of("checkout", beforeRef, "--", restorePath));
        } else {
            deletePath(root, file.path());
        }
    }

    private boolean existsAt(Path root, Path gitDir, String ref, String path) {
        if (!isTrackable(path)) {
            return false;
        }
        try {
            runGit(root, gitDir, 20, List.of("cat-file", "-e", ref + ":" + path));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void deletePath(Path root, String relativePath) throws IOException {
        if (!isTrackable(relativePath)) {
            return;
        }
        SecureWorkspacePath paths = new SecureWorkspacePath(root);
        Path target;
        try {
            target = paths.resolveExisting(relativePath);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.deleteIfExists(target);
        }
    }

    private void init(Path root, Path gitDir) throws IOException, InterruptedException {
        Files.createDirectories(gitDir.getParent());
        boolean newRepository = !Files.exists(gitDir.resolve("HEAD"));
        if (newRepository) {
            long initStartedNanos = System.nanoTime();
            log.info("GIT_SNAPSHOT_INIT_START gitDir={}", gitDir);
            run(root, List.of("git", "init", "--bare", gitDir.toString()), 60);
            // Tree snapshots never commit, so author identity is unnecessary. Keep line endings stable
            // for the private index and configure it only once when the repository is created.
            runGit(root, gitDir, 20, List.of("config", "core.autocrlf", "false"));
            log.info("GIT_SNAPSHOT_INIT_COMPLETE gitDir={} elapsedMs={}", gitDir, elapsedMs(initStartedNanos));
        }
        Path exclude = gitDir.resolve("info").resolve("exclude");
        Files.createDirectories(exclude.getParent());
        if (!Files.exists(exclude)) {
            Files.writeString(exclude, String.join("\n", ProjectScanPolicy.ignoredDirectoryNames()) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
    }

    private Path createEphemeralIndex(Path gitDir) throws IOException {
        Path index = Files.createTempFile(gitDir, "index-path-", ".tmp");
        Files.deleteIfExists(index);
        return index;
    }

    private CommandResult runGit(Path root, Path gitDir, int timeoutSeconds, List<String> args) throws IOException, InterruptedException {
        return runGit(root, gitDir, timeoutSeconds, args, null);
    }

    private CommandResult runGit(Path root, Path gitDir, int timeoutSeconds, List<String> args, Path indexFile) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--git-dir");
        command.add(gitDir.toString());
        command.add("--work-tree");
        command.add(root.toString());
        command.addAll(args);
        return run(root, command, timeoutSeconds, indexFile);
    }

    private CommandResult run(Path root, List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        return run(root, command, timeoutSeconds, null);
    }

    private CommandResult run(Path root, List<String> command, int timeoutSeconds, Path indexFile) throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        String operation = gitOperation(command);
        log.info("GIT_SNAPSHOT_COMMAND_START operation={} timeoutSeconds={}", operation, timeoutSeconds);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true);
        if (indexFile != null) {
            builder.environment().put("GIT_INDEX_FILE", indexFile.toString());
        }
        Process process = builder.start();
        OutputCollector collector = new OutputCollector(process.getInputStream(), MAX_OUTPUT);
        Thread reader = new Thread(collector, "labex-git-snapshot-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            reader.join(2_000);
            log.warn("GIT_SNAPSHOT_COMMAND_TIMEOUT operation={} timeoutSeconds={} elapsedMs={} outputChars={}",
                    operation, timeoutSeconds, elapsedMs(startedNanos), collector.output().length());
            throw new IOException("git command timeout");
        }
        reader.join(2_000);
        if (reader.isAlive()) {
            throw new IOException("git command output reader did not finish");
        }
        if (collector.error() != null) {
            throw collector.error();
        }
        String output = collector.output();
        if (process.exitValue() != 0) {
            log.warn("GIT_SNAPSHOT_COMMAND_FAILED operation={} exitCode={} elapsedMs={} outputChars={} truncated={}",
                    operation, process.exitValue(), elapsedMs(startedNanos), output.length(), collector.truncated());
            throw new IOException(limit(output, 4000));
        }
        log.info("GIT_SNAPSHOT_COMMAND_COMPLETE operation={} exitCode={} elapsedMs={} outputChars={} truncated={}",
                operation, process.exitValue(), elapsedMs(startedNanos), output.length(), collector.truncated());
        return new CommandResult(process.exitValue(), output);
    }

    private String gitOperation(List<String> command) {
        for (String value : command) {
            if (List.of("add", "status", "commit", "rev-parse", "diff", "config", "init", "checkout", "reset",
                    "write-tree", "cat-file", "show").contains(value)) {
                return value;
            }
        }
        return command == null || command.isEmpty() ? "unknown" : command.get(0);
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private List<String> buildArgs(String first, String second, String third, String fourth, String fifth, List<String> tail) {
        List<String> args = new ArrayList<>();
        args.add(first);
        args.add(second);
        args.add(third);
        args.add(fourth);
        args.add(fifth);
        args.addAll(tail);
        return args;
    }

    private List<String> buildArgs(String first, String second, String third, String fourth, List<String> tail) {
        List<String> args = new ArrayList<>();
        args.add(first);
        args.add(second);
        args.add(third);
        args.add(fourth);
        args.addAll(tail);
        return args;
    }

    private List<String> buildArgs(String first, String second, String third, List<String> tail) {
        List<String> args = new ArrayList<>();
        args.add(first);
        args.add(second);
        args.add(third);
        args.addAll(tail);
        return args;
    }

    private List<String> pathScopedAddArgs(List<String> paths) {
        List<String> args = new ArrayList<>();
        args.add("add");
        args.add("--all");
        args.add("--");
        args.addAll(paths);
        return args;
    }

    private List<String> trackablePaths(Collection<String> relativePaths) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String relativePath : relativePaths) {
            String normalized = normalizeGitPath(relativePath);
            if (isTrackable(normalized)) {
                paths.add(normalized);
            }
        }
        return List.copyOf(paths);
    }

    private List<String> excludedPathspecs() {
        List<String> pathspecs = new ArrayList<>();
        for (String name : ProjectScanPolicy.ignoredDirectoryNames()) {
            pathspecs.add(":(exclude,glob)**/" + name);
            pathspecs.add(":(exclude,glob)**/" + name + "/**");
        }
        return pathspecs;
    }

    private Path workspaceRoot(StudentProject project) {
        return new SecureWorkspacePath(Path.of(project.getWorkspacePath())).workspaceRoot();
    }

    private Path gitDir(Path root) {
        return new SecureWorkspacePath(root).resolveForCreate(".labex/git-snapshots");
    }

    private String safeLabel(String label) {
        String base = label == null || label.isBlank() ? "workspace snapshot" : label.replaceAll("[\\r\\n]+", " ").trim();
        return limit(base, 120);
    }

    private String normalizeGitPath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private boolean isTrackable(String path) {
        String p = normalizeGitPath(path);
        return isWorkspaceRelativePath(p) && !ProjectScanPolicy.isIgnoredRelativePath(p);
    }

    private boolean isWorkspaceRelativePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return false;
        }
        try {
            Path candidate = Path.of(normalized);
            if (candidate.isAbsolute()) {
                return false;
            }
            for (Path segment : candidate) {
                if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max) + "\n...[truncated]";
    }

    public record Snapshot(boolean available, String ref, String status, String error) {
        static Snapshot available(String ref, String status) {
            return new Snapshot(true, ref, status, "");
        }

        static Snapshot unavailable(String error) {
            return new Snapshot(false, "", "unavailable", error == null ? "" : error);
        }
    }

    public record ChangedFile(String status, String oldPath, String path) {
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicReference<IOException> error = new AtomicReference<>();
        private volatile boolean truncated;

        private OutputCollector(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8_192];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int remaining = limit - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                error.set(e);
            }
        }

        private String output() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private IOException error() {
            return error.get();
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}
