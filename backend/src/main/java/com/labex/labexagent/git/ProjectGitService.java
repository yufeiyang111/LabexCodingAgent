package com.labex.labexagent.git;

import com.labex.entity.StudentProject;
import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.workspace.WorkspaceLeaseService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户 git 提交：未提交改动、暂存、提交、历史、diff、回滚。
 *
 * <p>与 Agent 改动完全分开：本服务只认 git HEAD 与工作区，不知道 change-set 的存在。
 * Agent 的 diff/undo 由 {@code DiffService} 负责，两者唯一的接触点是工作区文件。
 *
 * <p>索引纪律：本服务只用仓库默认索引（用户暂存区），**永不传 {@code GIT_INDEX_FILE}**；
 * Agent 快照只用 {@code snapshot.index}。两条路径共用 {@link WorkspaceLeaseService} 的租约互斥。
 */
@Service
public class ProjectGitService {
    private static final Logger log = LoggerFactory.getLogger(ProjectGitService.class);
    /** log 输出的字段分隔符与记录分隔符：消息正文可能包含任意字符，用控制字符避免歧义。 */
    private static final String FIELD = "\u0001";
    private static final String RECORD = "\u001e";
    /** diff 里"空的一侧"：新文件与空设备比较，得到整份内容都是新增的补丁。 */
    private static final String EMPTY_SIDE = "/dev/null";

    private final GitCommandRunner git;
    private final ProjectGitRepository repository;
    private final ProjectGitProperties properties;
    private final WorkspaceLeaseService leases;

    public ProjectGitService(GitCommandRunner git, ProjectGitRepository repository,
                             ProjectGitProperties properties, WorkspaceLeaseService leases) {
        this.git = git;
        this.repository = repository;
        this.properties = properties;
        this.leases = leases;
    }

    /**
     * 工作区相对 HEAD 的未提交改动。
     *
     * <p>仓库还没有任何提交时同样要列出文件：全新项目的第一次提交只能靠这份清单，
     * 此时所有文件都是未跟踪状态。{@code initialized} 只表示"有没有提交历史"。
     */
    public ProjectGitDtos.StatusView status(StudentProject project) {
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try {
            repository.ensureInitialized(root, gitDir);
            String output = git.git(root, gitDir, properties.getQueryTimeoutSeconds(), List.of(
                    "status", "--porcelain=v1", "-z", "--untracked-files=all", "--find-renames")).output();
            List<ProjectGitDtos.StatusEntry> entries =
                    GitPorcelainParser.parseStatus(output, properties.getStatusMaxEntries());
            int staged = (int) entries.stream().filter(ProjectGitDtos.StatusEntry::staged).count();
            return new ProjectGitDtos.StatusView(repository.hasCommits(gitDir),
                    entries.size() - staged, staged, entries);
        } catch (IOException | InterruptedException e) {
            throw failure("读取未提交改动失败", e);
        }
    }

    /** 暂存勾选的文件。{@code paths} 为空表示暂存全部未提交改动。 */
    public void stage(StudentProject project, List<String> paths) {
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try (WorkspaceLeaseService.Lease ignored = acquire(project, root)) {
            repository.ensureInitialized(root, gitDir);
            List<String> args = new ArrayList<>(List.of("add", "--all"));
            List<String> targets = trackablePaths(paths);
            if (!targets.isEmpty()) {
                args.add("--");
                args.addAll(targets);
            }
            git.git(root, gitDir, properties.getWriteTimeoutSeconds(), args);
        } catch (IOException | InterruptedException e) {
            throw failure("暂存失败", e);
        }
    }

    /** 提交已暂存的改动。没有暂存内容时拒绝，避免产生空提交。 */
    public ProjectGitDtos.CommitResult commit(StudentProject project, String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            throw new ProjectGitException("提交信息不能为空");
        }
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try (WorkspaceLeaseService.Lease ignored = acquire(project, root)) {
            repository.ensureInitialized(root, gitDir);
            String staged = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("diff", "--cached", "--name-only")).output();
            if (staged.isBlank()) {
                throw new ProjectGitException("没有已暂存的改动，请先勾选文件");
            }
            commitWithMessage(root, gitDir, trimmed);
            String sha = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("rev-parse", "HEAD")).trimmed();
            int fileCount = countFiles(root, gitDir, sha);
            log.info("PROJECT_GIT_COMMIT projectId={} sha={} fileCount={}", project.getProjectId(), sha, fileCount);
            return new ProjectGitDtos.CommitResult(sha, shortSha(sha), trimmed, fileCount);
        } catch (IOException | InterruptedException e) {
            throw failure("提交失败", e);
        }
    }

    /** 提交历史，按时间倒序分页。{@code before} 是上一页最后一条的 sha。 */
    public ProjectGitDtos.CommitPage commits(StudentProject project, String before, Integer limit) {
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        // 仓库存在但还没有提交时 HEAD 指向未出生的分支，git log 会直接失败：
        // 「没有历史」要返回空列表，不能当成错误抛给前端。
        if (!Files.exists(gitDir.resolve("HEAD")) || !repository.hasCommits(gitDir)) {
            return new ProjectGitDtos.CommitPage(List.of(), null);
        }
        int pageSize = limit == null || limit <= 0
                ? properties.getCommitsPageSize()
                : Math.min(limit, properties.getCommitsPageSize());
        try {
            List<String> args = new ArrayList<>(List.of("log", "-n", String.valueOf(pageSize + 1),
                    "--format=%H" + FIELD + "%h" + FIELD + "%an" + FIELD + "%aI" + FIELD + "%P" + FIELD + "%s" + RECORD));
            if (before != null && !before.isBlank()) {
                // 游标是上一页最后一条提交；从它的父提交继续。根提交没有父，说明已经到底。
                String parent;
                try {
                    parent = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                            List.of("rev-parse", "--verify", before.trim() + "^{commit}^")).trimmed();
                } catch (IOException rootCommit) {
                    return new ProjectGitDtos.CommitPage(List.of(), null);
                }
                args.add(parent);
            }
            List<ProjectGitDtos.CommitSummary> commits = parseLog(
                    git.git(root, gitDir, properties.getQueryTimeoutSeconds(), args).output());
            String nextBefore = null;
            if (commits.size() > pageSize) {
                commits = new ArrayList<>(commits.subList(0, pageSize));
                nextBefore = commits.get(commits.size() - 1).sha();
            }
            return new ProjectGitDtos.CommitPage(commits, nextBefore);
        } catch (IOException | InterruptedException e) {
            throw failure("读取提交历史失败", e);
        }
    }

    /** 单次提交的文件清单与增删行数。 */
    public ProjectGitDtos.CommitDetail commitDetail(StudentProject project, String sha) {
        String resolved = requireSha(project, sha);
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try {
            ProjectGitDtos.CommitSummary summary = parseLog(git.git(root, gitDir,
                            properties.getQueryTimeoutSeconds(),
                            List.of("log", "-1", "--format=%H" + FIELD + "%h" + FIELD + "%an" + FIELD + "%aI"
                                    + FIELD + "%P" + FIELD + "%s" + RECORD, resolved)).output())
                    .stream().findFirst()
                    .orElseThrow(() -> new ProjectGitException("提交不存在"));
            List<ProjectGitDtos.CommitFile> files = GitPorcelainParser.parseNameStatus(git.git(root, gitDir,
                    properties.getQueryTimeoutSeconds(),
                    List.of("diff-tree", "--no-commit-id", "--name-status", "-M", "-r", "-z", "--root", resolved)).output());
            GitPorcelainParser.applyNumstat(git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("diff-tree", "--no-commit-id", "--numstat", "-M", "-r", "-z", "--root", resolved)).output(), files);
            return new ProjectGitDtos.CommitDetail(summary, files);
        } catch (IOException | InterruptedException e) {
            throw failure("读取提交详情失败", e);
        }
    }

    /** 某次提交相对其父提交的补丁。{@code path} 为空表示整个提交。不传 {@code --binary}，理由同 {@link #workingDiff}。 */
    public String commitDiff(StudentProject project, String sha, String path) {
        String resolved = requireSha(project, sha);
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try {
            List<String> args = new ArrayList<>(List.of("show", "--format=", "--patch",
                    "--find-renames", resolved));
            if (path != null && !path.isBlank()) {
                args.add("--");
                args.add(requireTrackablePath(path));
            }
            return GitCommandRunner.limit(git.git(root, gitDir, properties.getQueryTimeoutSeconds(), args).output(),
                    properties.getDiffMaxBytes());
        } catch (IOException | InterruptedException e) {
            throw failure("读取提交 diff 失败", e);
        }
    }

    /**
     * 未提交改动的单文件 diff：工作区（含暂存）相对 HEAD。
     *
     * <p>未跟踪文件在 HEAD 里没有条目，{@code git diff HEAD} 对它什么都不输出；仓库还没有
     * 第一条提交时整个工作区都属于这种情况。这两处都改走"空设备 vs 文件"，把整份内容
     * 当成新增，否则用户点开文件只会看到空 diff。
     *
     * <p>不传 {@code --binary}：二进制文件只显示 {@code Binary files ... differ} 一行，
     * 否则整段 base64 补丁会铺满面板。
     */
    public String workingDiff(StudentProject project, String path) {
        String relative = requireTrackablePath(path);
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try {
            repository.ensureInitialized(root, gitDir);
            String diff = repository.hasCommits(gitDir) && isTracked(root, gitDir, relative)
                    ? git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                            List.of("diff", "HEAD", "--patch", "--find-renames", "--", relative)).output()
                    : newFileDiff(root, gitDir, relative);
            return GitCommandRunner.limit(diff, properties.getDiffMaxBytes());
        } catch (IOException | InterruptedException e) {
            throw failure("读取改动 diff 失败", e);
        }
    }

    /**
     * 回滚到某次提交：把工作区与索引恢复到那次提交的树，并**新增一条 Revert 提交**。
     *
     * <p>不用 {@code git reset --hard}：那会让之后的提交从历史里消失，用户会以为数据丢了。
     * 新增反向提交意味着回滚本身也可以再回滚一次。
     *
     * <p>工作区有未提交改动时直接拒绝，不覆盖用户正在写的内容。
     */
    public ProjectGitDtos.CommitResult revert(StudentProject project, String sha) {
        String resolved = requireSha(project, sha);
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try (WorkspaceLeaseService.Lease ignored = acquire(project, root)) {
            repository.ensureInitialized(root, gitDir);
            String dirty = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("status", "--porcelain=v1", "--untracked-files=all")).output();
            if (!dirty.isBlank()) {
                throw new ProjectGitException("工作区有未提交改动，请先提交或撤销后再回滚");
            }
            String targetTree = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("rev-parse", resolved + "^{tree}")).trimmed();
            String headTree = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("rev-parse", "HEAD^{tree}")).trimmed();
            if (targetTree.equals(headTree)) {
                throw new ProjectGitException("当前工作区已经与该提交一致，无需回滚");
            }
            String subject = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("log", "-1", "--format=%s", resolved)).trimmed();
            git.git(root, gitDir, properties.getWriteTimeoutSeconds(),
                    List.of("read-tree", "--reset", "-u", resolved));
            String message = "Revert \"" + subject + "\"";
            commitWithMessage(root, gitDir, message);
            String newSha = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                    List.of("rev-parse", "HEAD")).trimmed();
            int fileCount = countFiles(root, gitDir, newSha);
            log.info("PROJECT_GIT_REVERT projectId={} target={} newSha={} fileCount={}",
                    project.getProjectId(), resolved, newSha, fileCount);
            return new ProjectGitDtos.CommitResult(newSha, shortSha(newSha), message, fileCount);
        } catch (IOException | InterruptedException e) {
            throw failure("回滚失败", e);
        }
    }

    /**
     * 用临时文件传提交信息，而不是 {@code -m <message>}。
     *
     * <p>Windows 上 ProcessBuilder 对含内嵌双引号的参数转义不完整：{@code Revert "abc"} 会被
     * 拆成多个 argv，git 把后半段当成 pathspec 报 "did not match any file(s)"。走 stdin 文件
     * 既避开转义，也天然支持多行提交信息。
     */
    private void commitWithMessage(Path root, Path gitDir, String message) throws IOException, InterruptedException {
        Path messageFile = Files.createTempFile(gitDir, "commit-message-", ".txt");
        try {
            Files.writeString(messageFile, message, StandardCharsets.UTF_8);
            git.git(root, gitDir, properties.getWriteTimeoutSeconds(),
                    List.of("commit", "--file", messageFile.toString()));
        } finally {
            Files.deleteIfExists(messageFile);
        }
    }

    /** 仓库是否已经有提交。前端据此区分"首次提交"与"日常提交"。 */
    public boolean initialized(StudentProject project) {
        return repository.hasCommits(repository.gitDir(workspaceRoot(project)));
    }

    /** 文件是否已进索引。未跟踪文件在索引与 HEAD 里都没有条目。 */
    private boolean isTracked(Path root, Path gitDir, String relative) throws IOException, InterruptedException {
        return !git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                List.of("ls-files", "--", relative)).output().isBlank();
    }

    /**
     * 还没有基线的文件：拿空设备和它比较，整份内容都算新增。
     *
     * <p>{@code /dev/null} 由 git 自己解释，Windows 上不需要换成 {@code NUL}。
     */
    private String newFileDiff(Path root, Path gitDir, String relative) throws IOException, InterruptedException {
        return git.gitDiffNoIndex(root, gitDir, properties.getQueryTimeoutSeconds(),
                List.of("diff", "--no-index", "--patch", "--", EMPTY_SIDE, relative)).output();
    }

    private int countFiles(Path root, Path gitDir, String sha) throws IOException, InterruptedException {
        String output = git.git(root, gitDir, properties.getQueryTimeoutSeconds(),
                List.of("diff-tree", "--no-commit-id", "--name-only", "-r", "-z", "--root", sha)).output();
        int count = 0;
        for (String entry : output.split("\u0000")) {
            if (!entry.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private List<ProjectGitDtos.CommitSummary> parseLog(String output) {
        List<ProjectGitDtos.CommitSummary> commits = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return commits;
        }
        for (String record : output.split(RECORD)) {
            if (record.isBlank()) {
                continue;
            }
            String[] fields = record.replace("\n", "").split(FIELD, -1);
            if (fields.length < 6) {
                continue;
            }
            List<String> parents = fields[4].isBlank() ? List.of() : List.of(fields[4].trim().split("\\s+"));
            commits.add(new ProjectGitDtos.CommitSummary(fields[0], fields[1], fields[5], fields[2], fields[3], parents));
        }
        return commits;
    }

    private String requireSha(StudentProject project, String sha) {
        String value = sha == null ? "" : sha.trim();
        if (!value.matches("^[0-9a-fA-F]{4,40}$")) {
            throw new ProjectGitException("提交标识无效");
        }
        Path root = workspaceRoot(project);
        try {
            git.git(root, repository.gitDir(root), properties.getQueryTimeoutSeconds(),
                    List.of("rev-parse", "--verify", value + "^{commit}"));
            return value;
        } catch (IOException e) {
            throw new ProjectGitException("提交不存在：" + value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProjectGitException("读取提交被中断");
        }
    }

    private String requireTrackablePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../")) {
            throw new ProjectGitException("路径必须是工作区相对路径");
        }
        if (ProjectScanPolicy.isIgnoredRelativePath(normalized)) {
            throw new ProjectGitException("该路径不在版本库管理范围内");
        }
        return normalized;
    }

    private List<String> trackablePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            result.add(requireTrackablePath(path));
        }
        return List.copyOf(result);
    }

    private WorkspaceLeaseService.Lease acquire(StudentProject project, Path root) {
        String checkoutId = leases.checkoutId(project.getProjectId(), root);
        return leases.acquire(checkoutId, "project-git:" + project.getProjectId());
    }

    private Path workspaceRoot(StudentProject project) {
        SecureWorkspacePath paths = ProjectWorkspace.paths(project);
        return paths.workspaceRoot();
    }

    private String shortSha(String sha) {
        return sha.substring(0, Math.min(7, sha.length()));
    }

    private ProjectGitException failure(String action, Exception cause) {
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String detail = cause.getMessage() == null ? "" : cause.getMessage().trim();
        log.warn("PROJECT_GIT_FAILURE action={} errorType={} detail={}",
                action, cause.getClass().getSimpleName(), detail);
        return new ProjectGitException(detail.isBlank() ? action : action + "：" + detail, cause);
    }

    /** 面向用户的版本库操作失败。消息可直接展示。 */
    public static class ProjectGitException extends RuntimeException {
        public ProjectGitException(String message) {
            super(message);
        }

        public ProjectGitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
