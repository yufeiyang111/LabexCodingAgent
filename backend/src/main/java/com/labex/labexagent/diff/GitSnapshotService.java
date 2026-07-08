package com.labex.labexagent.diff;

import com.labex.entity.StudentProject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GitSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(GitSnapshotService.class);
    private static final int MAX_OUTPUT = 120_000;
    private static final List<String> EXCLUDED_PATHS = List.of(
            ":(exclude).git",
            ":(exclude).git/**",
            ":(exclude).labex",
            ":(exclude).labex/**",
            ":(exclude)node_modules",
            ":(exclude)node_modules/**",
            ":(exclude)dist",
            ":(exclude)dist/**",
            ":(exclude)build",
            ":(exclude)build/**",
            ":(exclude)target",
            ":(exclude)target/**",
            ":(exclude).gradle",
            ":(exclude).gradle/**",
            ":(exclude)__pycache__",
            ":(exclude)__pycache__/**"
    );

    public Snapshot capture(StudentProject project, String label) {
        try {
            Path root = workspaceRoot(project);
            if (!Files.isDirectory(root)) {
                return Snapshot.unavailable("workspace not found");
            }
            Path gitDir = gitDir(root);
            init(root, gitDir);
            boolean hasHead = hasHead(root, gitDir);
            runGit(root, gitDir, 60, buildArgs("add", "-A", "--", ".", EXCLUDED_PATHS));
            String status = runGit(root, gitDir, 60, buildArgs("status", "--porcelain", "--untracked-files=all", "--", ".", EXCLUDED_PATHS)).output();
            if (status.isBlank() && hasHead) {
                String current = runGit(root, gitDir, 20, List.of("rev-parse", "HEAD")).output().trim();
                return Snapshot.available(current, "clean");
            }
            runGit(root, gitDir, 60, List.of("commit", "--allow-empty", "-m", safeLabel(label)));
            String ref = runGit(root, gitDir, 20, List.of("rev-parse", "HEAD")).output().trim();
            return Snapshot.available(ref, status.isBlank() ? "empty" : "changed");
        } catch (Exception e) {
            log.debug("Workspace snapshot unavailable: {}", e.getMessage());
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
        if (ref == null || ref.isBlank() || path == null || path.isBlank()) {
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
        try {
            runGit(root, gitDir, 20, List.of("cat-file", "-e", ref + ":" + path));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void deletePath(Path root, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
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
        if (!Files.exists(gitDir.resolve("HEAD"))) {
            run(root, List.of("git", "init", "--bare", gitDir.toString()), 60);
        }
        runGit(root, gitDir, 20, List.of("config", "user.email", "labex-agent@local"));
        runGit(root, gitDir, 20, List.of("config", "user.name", "Labex Agent"));
        runGit(root, gitDir, 20, List.of("config", "core.autocrlf", "false"));
        Path exclude = gitDir.resolve("info").resolve("exclude");
        Files.createDirectories(exclude.getParent());
        if (!Files.exists(exclude)) {
            Files.writeString(exclude, ".labex/\n.git/\nnode_modules/\ndist/\nbuild/\ntarget/\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE);
        }
    }

    private boolean hasHead(Path root, Path gitDir) {
        try {
            runGit(root, gitDir, 20, List.of("rev-parse", "--verify", "HEAD"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private CommandResult runGit(Path root, Path gitDir, int timeoutSeconds, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--git-dir");
        command.add(gitDir.toString());
        command.add("--work-tree");
        command.add(root.toString());
        command.addAll(args);
        return run(root, command, timeoutSeconds);
    }

    private CommandResult run(Path root, List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git command timeout");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException(limit(output, 4000));
        }
        return new CommandResult(process.exitValue(), output);
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

    private Path workspaceRoot(StudentProject project) {
        return Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
    }

    private Path gitDir(Path root) {
        return root.resolve(".labex").resolve("git-snapshots");
    }

    private String safeLabel(String label) {
        String base = label == null || label.isBlank() ? "workspace snapshot" : label.replaceAll("[\\r\\n]+", " ").trim();
        return limit(base, 120) + " @ " + LocalDateTime.now();
    }

    private String normalizeGitPath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private boolean isTrackable(String path) {
        String p = normalizeGitPath(path);
        return !p.isBlank()
                && !p.startsWith(".git/")
                && !p.equals(".git")
                && !p.startsWith(".labex/")
                && !p.equals(".labex")
                && !p.startsWith("node_modules/")
                && !p.startsWith("dist/")
                && !p.startsWith("build/")
                && !p.startsWith("target/");
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

    private record CommandResult(int exitCode, String output) {
    }
}
