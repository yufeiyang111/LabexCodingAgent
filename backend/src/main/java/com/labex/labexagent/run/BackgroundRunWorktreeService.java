package com.labex.labexagent.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class BackgroundRunWorktreeService {
    private static final long COMMAND_TIMEOUT_SECONDS = 60;

    public Allocation allocate(Path workspaceRoot, long taskId) throws IOException, InterruptedException {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path gitDir = root.resolve(".labex").resolve("git");
        if (!Files.isRegularFile(gitDir.resolve("HEAD"))) {
            throw new IllegalStateException("background worktree requires an initialized .labex/git repository");
        }
        String branch = "agent/run-" + taskId;
        Path worktreeRoot = root.resolve(".labex").resolve("background-runs").toAbsolutePath().normalize();
        Path worktree = worktreeRoot.resolve(Long.toString(taskId)).normalize();
        if (!worktree.startsWith(worktreeRoot)) {
            throw new IllegalArgumentException("worktree escapes managed directory");
        }
        if (Files.isDirectory(worktree)) {
            return new Allocation(branch, worktree, resolveHead(gitDir));
        }
        Files.createDirectories(worktreeRoot);
        String baseRef = resolveHead(gitDir);
        run(gitDir, List.of("worktree", "add", "-b", branch, worktree.toString(), baseRef));
        return new Allocation(branch, worktree, baseRef);
    }

    public void cleanup(Path workspaceRoot, long taskId) throws IOException, InterruptedException {
        if (taskId <= 0) throw new IllegalArgumentException("taskId must be positive");
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path gitDir = root.resolve(".labex").resolve("git");
        Path managedRoot = root.resolve(".labex").resolve("background-runs").toAbsolutePath().normalize();
        Path worktree = managedRoot.resolve(Long.toString(taskId)).normalize();
        if (!worktree.startsWith(managedRoot) || !Files.exists(worktree)) return;
        run(gitDir, List.of("worktree", "remove", "--force", worktree.toString()));
    }

    private String resolveHead(Path gitDir) throws IOException, InterruptedException {
        return run(gitDir, List.of("rev-parse", "HEAD")).trim();
    }

    private String run(Path gitDir, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("git"); command.add("--git-dir=" + gitDir); command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IOException("git command timed out");
        }
        if (process.exitValue() != 0) throw new IOException("git command failed: " + output.trim());
        return output;
    }

    public record Allocation(String branch, Path worktree, String baseRef) { }
}
