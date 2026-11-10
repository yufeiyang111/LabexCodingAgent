package com.labex.labexagent.git;

import com.labex.entity.StudentProject;
import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 版本库的只读查询：历史、单次提交详情、提交级 diff、未提交 diff。
 *
 * <p>与 {@link ProjectGitService} 分开的原因：写操作（stage / commit / revert）必须持有
 * {@code WorkspaceLeaseService} 的租约，读操作不需要；把两者放在一个类里，读路径会顺带继承
 * 写路径的锁语义，也让「哪些方法会改工作区」变得不明显。这里的方法一律只读。
 *
 * <p>索引纪律同 {@link ProjectGitService}：只读查询只用仓库默认索引，永不传 {@code GIT_INDEX_FILE}。
 */
@Service
public class ProjectGitHistoryService {
    /** log 输出的字段分隔符与记录分隔符：消息正文可能包含任意字符，用控制字符避免歧义。 */
    private static final String FIELD = "\u0001";
    private static final String RECORD = "\u001e";
    /** diff 里"空的一侧"：新文件与空设备比较，得到整份内容都是新增的补丁。 */
    private static final String EMPTY_SIDE = "/dev/null";

    private final GitCommandRunner git;
    private final ProjectGitRepository repository;
    private final ProjectGitProperties properties;

    public ProjectGitHistoryService(GitCommandRunner git, ProjectGitRepository repository,
                                    ProjectGitProperties properties) {
        this.git = git;
        this.repository = repository;
        this.properties = properties;
    }

    /** 提交历史，按时间倒序分页。{@code before} 是上一页最后一条的 sha。 */
    public ProjectGitDtos.CommitPage commits(StudentProject project, String before, Integer limit) {
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        // 读历史也要先确保仓库就绪：工作区的 .git gitfile 是「这个项目有自己的仓库」的标志，
        // 少了它，Agent 在工作区里跑 git log 会向上找到外层目录的仓库，报出别人的历史。
        // 仓库存在但还没有提交时 HEAD 指向未出生的分支，git log 会直接失败：
        // 「没有历史」要返回空列表，不能当成错误抛给前端。
        try {
            repository.ensureInitialized(root, gitDir);
        } catch (IOException | InterruptedException e) {
            throw failure("读取提交历史失败", e);
        }
        if (!repository.hasCommits(gitDir)) {
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
        String resolved = requireExistingCommit(git, properties, repository, project, sha);
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try {
            ProjectGitDtos.CommitSummary summary = parseLog(git.git(root, gitDir,
                            properties.getQueryTimeoutSeconds(),
                            List.of("log", "-1", "--format=%H" + FIELD + "%h" + FIELD + "%an" + FIELD + "%aI"
                                    + FIELD + "%P" + FIELD + "%s" + RECORD, resolved)).output())
                    .stream().findFirst()
                    .orElseThrow(() -> new ProjectGitService.ProjectGitException("提交不存在"));
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
        String resolved = requireExistingCommit(git, properties, repository, project, sha);
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

    private String requireTrackablePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../")) {
            throw new ProjectGitService.ProjectGitException("路径必须是工作区相对路径");
        }
        if (ProjectScanPolicy.isIgnoredRelativePath(normalized)) {
            throw new ProjectGitService.ProjectGitException("该路径不在版本库管理范围内");
        }
        return normalized;
    }

    private Path workspaceRoot(StudentProject project) {
        SecureWorkspacePath paths = ProjectWorkspace.paths(project);
        return paths.workspaceRoot();
    }

    private ProjectGitService.ProjectGitException failure(String action, Exception cause) {
        return ProjectGitService.failure(action, cause);
    }

    /**
     * 校验提交标识并确认它在本仓库里存在。
     *
     * <p>静态且包内可见：{@link ProjectGitService#revert} 也要用它，两个类各写一份会让
     * 「什么算合法 sha」出现两个版本。
     */
    static String requireExistingCommit(GitCommandRunner git, ProjectGitProperties properties,
                                        ProjectGitRepository repository, StudentProject project, String sha) {
        String value = sha == null ? "" : sha.trim();
        if (!value.matches("^[0-9a-fA-F]{4,40}$")) {
            throw new ProjectGitService.ProjectGitException("提交标识无效");
        }
        Path root = ProjectWorkspace.paths(project).workspaceRoot();
        try {
            git.git(root, repository.gitDir(root), properties.getQueryTimeoutSeconds(),
                    List.of("rev-parse", "--verify", value + "^{commit}"));
            return value;
        } catch (IOException e) {
            throw new ProjectGitService.ProjectGitException("提交不存在：" + value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProjectGitService.ProjectGitException("读取提交被中断");
        }
    }
}
