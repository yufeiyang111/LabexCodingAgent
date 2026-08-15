package com.labex.labexagent.service;

import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Traverses workspace files with mandatory safety checks, directory pruning and bounded resource use.
 * Callers decide which eligible files to consume but cannot bypass the workspace scan policy.
 */
@Service
public class WorkspaceScanner {
    public static final ScanBudget INTERACTIVE_SEARCH_BUDGET = new ScanBudget(10_000, 2_000, 20, 3_000);

    public ScanResult scan(SecureWorkspacePath paths, ScanBudget budget, CancellationToken cancellationToken,
                           FileHandler fileHandler) throws IOException {
        return scan(paths, paths == null ? null : paths.workspaceRoot(), budget, cancellationToken, fileHandler);
    }

    /** 在已校验的 workspace 子目录内扫描，调用方不能借此绕过 SecureWorkspacePath 边界。 */
    public ScanResult scan(SecureWorkspacePath paths, Path requestedRoot, ScanBudget budget,
                           CancellationToken cancellationToken, FileHandler fileHandler) throws IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(fileHandler, "fileHandler");
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        Path root = resolveDirectoryRoot(paths, requestedRoot);
        ProjectScanPolicy.ScanIgnoreRules ignoreRules = ProjectScanPolicy.loadIgnoreRules(paths);
        ScanState state = new ScanState(System.nanoTime());

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (shouldStop(state, budget, token)) return FileVisitResult.TERMINATE;
                state.visitedEntries++;
                if (!ProjectScanPolicy.isSafeWorkspaceEntry(paths, directory)
                        || ignoreRules.shouldSkipDirectory(directory)) {
                    state.skippedDirectories++;
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (root.relativize(directory).getNameCount() > budget.maxDepth()) {
                    state.depthLimitedDirectories++;
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (shouldStop(state, budget, token)) return FileVisitResult.TERMINATE;
                state.visitedEntries++;
                if (!attributes.isRegularFile() || !ProjectScanPolicy.isSafeWorkspaceEntry(paths, file)
                        || ignoreRules.shouldSkipFile(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (state.candidateFiles >= budget.maxCandidateFiles()) {
                    state.stopReason = StopReason.CANDIDATE_LIMIT;
                    return FileVisitResult.TERMINATE;
                }
                state.candidateFiles++;
                if (!fileHandler.handle(file, attributes)) {
                    state.stopReason = StopReason.RESULT_LIMIT;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return state.result();
    }

    private Path resolveDirectoryRoot(SecureWorkspacePath paths, Path requestedRoot) {
        Path workspaceRoot = paths.workspaceRoot();
        Path candidate = requestedRoot == null ? workspaceRoot : requestedRoot.toAbsolutePath().normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("scan root escapes workspace");
        }
        String relative = workspaceRoot.relativize(candidate).toString();
        Path verified = paths.resolveExisting(relative.isBlank() ? "." : relative);
        if (!Files.isDirectory(verified, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("scan root must be a directory");
        }
        return verified;
    }
    public ProjectScanPolicy.ScanIgnoreRules ignoreRules(SecureWorkspacePath paths) {
        return ProjectScanPolicy.loadIgnoreRules(paths);
    }

    public boolean isSafeWorkspaceEntry(SecureWorkspacePath paths, Path entry) {
        return ProjectScanPolicy.isSafeWorkspaceEntry(paths, entry);
    }

    private boolean shouldStop(ScanState state, ScanBudget budget, CancellationToken token) {
        if (token.isCancellationRequested()) {
            state.stopReason = StopReason.CANCELLED;
            return true;
        }
        if (state.visitedEntries >= budget.maxVisitedEntries()) {
            state.stopReason = StopReason.ENTRY_LIMIT;
            return true;
        }
        if (elapsedMillis(state.startedAt) >= budget.maxDurationMillis()) {
            state.stopReason = StopReason.TIME_LIMIT;
            return true;
        }
        return false;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    @FunctionalInterface
    public interface FileHandler {
        boolean handle(Path file, BasicFileAttributes attributes) throws IOException;
    }

    public record ScanBudget(int maxVisitedEntries, int maxCandidateFiles, int maxDepth, long maxDurationMillis) {
        public ScanBudget {
            if (maxVisitedEntries < 1 || maxCandidateFiles < 1 || maxDepth < 0 || maxDurationMillis < 1) {
                throw new IllegalArgumentException("Scan budget values must be positive");
            }
        }
    }

    public record ScanResult(int visitedEntries, int candidateFiles, int skippedDirectories,
                             int depthLimitedDirectories, StopReason stopReason, long elapsedMillis) {
        public boolean truncated() {
            return stopReason != StopReason.COMPLETED;
        }
    }

    public enum StopReason {
        COMPLETED, RESULT_LIMIT, ENTRY_LIMIT, CANDIDATE_LIMIT, TIME_LIMIT, CANCELLED
    }

    private static final class ScanState {
        private final long startedAt;
        private int visitedEntries;
        private int candidateFiles;
        private int skippedDirectories;
        private int depthLimitedDirectories;
        private StopReason stopReason = StopReason.COMPLETED;

        private ScanState(long startedAt) {
            this.startedAt = startedAt;
        }

        private ScanResult result() {
            return new ScanResult(visitedEntries, candidateFiles, skippedDirectories, depthLimitedDirectories,
                    stopReason, (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }
}
