package com.labex.labexagent.diff;

import com.labex.entity.StudentProject;
import com.labex.labexagent.git.GitCommandRunner;
import com.labex.labexagent.git.ProjectGitRepository;
import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 改动的树快照：{@code git add} + {@code write-tree}，不做 commit。
 *
 * <p>快照只用仓库内的 {@code snapshot.index}，与用户暂存区（仓库默认 {@code index}）物理隔离。
 * 该索引持久保留，因为 git 的 stat 缓存能把重复快照的成本从"重新哈希全部文件"降到
 * "只哈希变了的文件"：10k 文件实测 40s → 0.5s。索引文件本身不会污染用户可见状态，
 * 因为它不属于任何提交，也不会被 {@code git status} 读取。
 */
@Service
public class GitSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(GitSnapshotService.class);
    private static final int MAX_OUTPUT = GitCommandRunner.MAX_OUTPUT;
    /**
     * 文本内容被截断时追加的标记。
     * 公开给同步供给（B1）判定内容完整性：被截断的内容不能作为本地回退依据。
     * 注意：只要这里改了，{@code AgentSyncFeedService#isTruncated} 必须同时生效。
     */
    public static final String TRUNCATION_MARKER = GitCommandRunner.TRUNCATION_MARKER;
    private static final int SNAPSHOT_TIMEOUT_SECONDS = 120;

    private final GitCommandRunner git;
    private final ProjectGitRepository repository;

    public GitSnapshotService(GitCommandRunner git, ProjectGitRepository repository) {
        this.git = git;
        this.repository = repository;
    }

    /**
     * Captures every trackable workspace file. This remains the compatibility entry point for
     * tools whose writes cannot be reduced to a verified path list (for example shell commands).
     */
    public Snapshot capture(StudentProject project, String label) {
        return captureInternal(project, label, List.of(), false);
    }

    /**
     * Captures only the supplied, workspace-relative paths. The snapshot index is updated with
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
            Path gitDir = repository.gitDir(root);
            repository.ensureInitialized(root, gitDir);
            Path snapshotIndex = repository.snapshotIndex(gitDir);

            long stageStartedNanos = System.nanoTime();
            if (pathScoped) {
                git.git(root, gitDir, SNAPSHOT_TIMEOUT_SECONDS, pathScopedAddArgs(paths), snapshotIndex);
            } else {
                git.git(root, gitDir, SNAPSHOT_TIMEOUT_SECONDS, List.of("add", "-A", "--", "."), snapshotIndex);
            }
            long stageElapsedMs = elapsedMs(stageStartedNanos);

            long writeTreeStartedNanos = System.nanoTime();
            String ref = git.git(root, gitDir, SNAPSHOT_TIMEOUT_SECONDS, List.of("write-tree"), snapshotIndex)
                    .trimmed();
            if (ref.isBlank()) {
                throw new IOException("git write-tree returned an empty tree ref");
            }
            long writeTreeElapsedMs = elapsedMs(writeTreeStartedNanos);
            Snapshot snapshot = Snapshot.available(ref, "tree");
            log.info("GIT_SNAPSHOT_CAPTURE_COMPLETE projectId={} label={} scope={} pathCount={} state={} refPrefix={} stageMs={} writeTreeMs={} elapsedMs={}",
                    projectId, safeLabel, scope, paths.size(), snapshot.status(),
                    ref.substring(0, Math.min(12, ref.length())),
                    stageElapsedMs, writeTreeElapsedMs, elapsedMs(startedNanos));
            return snapshot;
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
            Path gitDir = repository.gitDir(root);
            String output = git.git(root, gitDir, 60,
                    List.of("diff", "--name-status", "-M", before.ref(), after.ref(), "--")).output();
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
            Path gitDir = repository.gitDir(root);
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
            return limit(git.git(root, gitDir, 60, args).output(), MAX_OUTPUT);
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
            Path gitDir = repository.gitDir(root);
            return limit(git.git(root, gitDir, 30, List.of("show", ref + ":" + path)).output(), MAX_OUTPUT);
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
            Path gitDir = repository.gitDir(root);
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
            git.git(root, gitDir, 60, List.of("checkout", beforeRef, "--", restorePath));
        } else {
            deletePath(root, file.path());
        }
    }

    private boolean existsAt(Path root, Path gitDir, String ref, String path) {
        if (!isTrackable(path)) {
            return false;
        }
        try {
            git.git(root, gitDir, 20, List.of("cat-file", "-e", ref + ":" + path));
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

    private Path workspaceRoot(StudentProject project) {
        return new SecureWorkspacePath(Path.of(project.getWorkspacePath())).workspaceRoot();
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
        return GitCommandRunner.limit(value, max);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
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
}
