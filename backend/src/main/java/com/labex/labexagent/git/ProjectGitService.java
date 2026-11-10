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
     *
     * <p>{@code --branch} 让 {@code status} 顺带返回分支头，{@code initialized} 直接从分支头
     * 读出来，不再单独跑 {@code rev-parse --verify HEAD}。Windows 上每多一个子进程就是 200ms
     * 以上，而且 {@code ProcessImpl.create} 是全局同步方法，会拖住同进程内所有其它进程创建。
     */
    public ProjectGitDtos.StatusView status(StudentProject project) {
        Path root = workspaceRoot(project);
        Path gitDir = repository.gitDir(root);
        try {
            repository.ensureInitialized(root, gitDir);
            String output = git.git(root, gitDir, properties.getQueryTimeoutSeconds(), List.of(
                    "status", "--porcelain=v1", "-z", "--untracked-files=all", "--find-renames",
                    "--branch")).output();
            List<ProjectGitDtos.StatusEntry> entries =
                    GitPorcelainParser.parseStatus(output, properties.getStatusMaxEntries());
            Boolean initialized = GitPorcelainParser.hasCommitsFromStatus(output);
            int staged = (int) entries.stream().filter(ProjectGitDtos.StatusEntry::staged).count();
            return new ProjectGitDtos.StatusView(
                    initialized != null ? initialized : repository.hasCommits(gitDir),
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

    /**
     * 回滚到某次提交：把工作区与索引恢复到那次提交的树，并**新增一条 Revert 提交**。
     *
     * <p>不用 {@code git reset --hard}：那会让之后的提交从历史里消失，用户会以为数据丢了。
     * 新增反向提交意味着回滚本身也可以再回滚一次。
     *
     * <p>工作区有未提交改动时直接拒绝，不覆盖用户正在写的内容。
     */
    public ProjectGitDtos.CommitResult revert(StudentProject project, String sha) {
        String resolved = ProjectGitHistoryService.requireExistingCommit(git, properties, repository, project, sha);
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

    /** 仓库是否已经有提交。前端据此区分"首次提交"与"日常提交"。 */
    public boolean initialized(StudentProject project) {
        return repository.hasCommits(repository.gitDir(workspaceRoot(project)));
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

    /**
     * 提交信息先写进工作区临时文件再传给 {@code git commit --file}。
     *
     * <p>不走 {@code -m}：消息里的换行、引号、冒号在命令行上要转义，Windows 与 Linux 的
     * 转义规则还不一样；写文件交给 git 自己读，跨平台一致。
     */
    private void commitWithMessage(Path root, Path gitDir, String message) throws IOException, InterruptedException {
        Path messageFile = root.resolve(".labex-agent").resolve("commit-message-" + System.nanoTime() + ".txt");
        Files.createDirectories(messageFile.getParent());
        try {
            Files.writeString(messageFile, message, StandardCharsets.UTF_8);
            git.git(root, gitDir, properties.getWriteTimeoutSeconds(),
                    List.of("commit", "--file", messageFile.toString()));
        } finally {
            Files.deleteIfExists(messageFile);
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

    /**
     * 统一的失败包装：日志 + 可展示消息。静态是因为 {@link ProjectGitHistoryService}
     * 也用同一套措辞，两个类各写一份会让同一个 git 错误出现两种文案。
     */
    static ProjectGitException failure(String action, Exception cause) {
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
