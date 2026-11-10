package com.labex.labexagent.git;

import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 项目版本库的位置、初始化与排除规则。
 *
 * <p>仓库是工作区内的 bare 仓库 {@code <workspace>/.labex/git-snapshots}，唯一一份。
 * 工作区不写 {@code .gitignore}：那是用户的文件，系统不该改写。排除规则写进仓库自己的
 * {@code info/exclude}，语义等价且不侵入工作区。
 *
 * <p>三个索引互不相干，任何代码不得混用：
 * <ul>
 *   <li>{@code index}（仓库默认）：用户暂存区，只有 {@link ProjectGitService} 写；</li>
 *   <li>{@code snapshot.index}：Agent 快照专用，只有 {@link GitSnapshotService} 写；</li>
 *   <li>{@code rebuild.index}：历史重建脚本一次性使用。</li>
 * </ul>
 * 快照索引是持久文件而非临时文件：git 的 stat 缓存能把 10k 文件的重复快照从 40 秒降到 0.5 秒
 * （见 {@code git/README.md} §5）。索引隔离靠"不同文件"实现，不靠"用完就删"。
 */
@Service
public class ProjectGitRepository {
    private static final Logger log = LoggerFactory.getLogger(ProjectGitRepository.class);
    public static final String GIT_DIR_NAME = ".labex/git-snapshots";
    public static final String SNAPSHOT_INDEX_NAME = "snapshot.index";
    public static final String REBUILD_INDEX_NAME = "rebuild.index";
    /** 工作区根上的 gitfile：把裸仓库暴露成「这个项目的仓库」，让 `git log` 不必带参数就能用。 */
    public static final String GIT_FILE_NAME = ".git";
    static final String COMMITTER_NAME = "Labex Agent";
    static final String COMMITTER_EMAIL = "labex-agent@local";
    /**
     * 仓库本地配置：换机器/新克隆都要一致，且不能让工作区的 .gitattributes 改变行为。
     *
     * <p>{@code core.bare=false} + {@code core.worktree=../..}：仓库目录是
     * {@code <workspace>/.labex/git-snapshots}，向上两级就是工作区根。这两项让 Agent 在
     * 工作区里直接跑 {@code git status} / {@code git log}（不带 {@code --git-dir}）也能工作，
     * 而不是因为「裸仓库没有工作树」而报错，或者更糟——向上找到别人的仓库。
     */
    private static final Map<String, String> REQUIRED_CONFIG = Map.of(
            "core.autocrlf", "false",
            "core.longpaths", "true",
            "core.symlinks", "true",
            "core.quotepath", "false",
            "core.bare", "false",
            "core.worktree", "../..",
            "user.name", COMMITTER_NAME,
            "user.email", COMMITTER_EMAIL);

    /** 已完成配置校验的仓库路径。配置在进程内不会自己变化，缓存避免每次快照多跑子进程。 */
    private final Set<String> configured = ConcurrentHashMap.newKeySet();

    private final GitCommandRunner git;

    public ProjectGitRepository(GitCommandRunner git) {
        this.git = git;
    }

    public Path gitDir(Path workspaceRoot) {
        return new SecureWorkspacePath(workspaceRoot).resolveForCreate(GIT_DIR_NAME);
    }

    public Path snapshotIndex(Path gitDir) {
        return gitDir.resolve(SNAPSHOT_INDEX_NAME);
    }

    public Path rebuildIndex(Path gitDir) {
        return gitDir.resolve(REBUILD_INDEX_NAME);
    }

    /**
     * 幂等初始化：仓库不存在则 {@code git init --bare}，然后校正配置与排除规则。
     *
     * <p>排除规则每次调用都比对内容（相同则不落盘），这样 {@link ProjectScanPolicy} 的名单变更
     * 会在下一次访问时自动生效，不需要额外的迁移步骤。
     *
     * <p>配置校验在一个进程内只做一次：快照热路径上每次多跑 7 个 git 子进程要 3 秒，
     * 而配置在进程生命周期内不会自己变。仓库被删除时 {@code HEAD} 不存在，缓存自动失效。
     */
    public void ensureInitialized(Path workspaceRoot, Path gitDir) throws IOException, InterruptedException {
        Files.createDirectories(gitDir.getParent());
        if (!Files.exists(gitDir.resolve("HEAD"))) {
            long startedNanos = System.nanoTime();
            log.info("GIT_REPOSITORY_INIT_START gitDir={}", gitDir);
            git.run(workspaceRoot, List.of("git", "init", "--bare", "--initial-branch=master", gitDir.toString()), 60);
            log.info("GIT_REPOSITORY_INIT_COMPLETE gitDir={} elapsedMs={}",
                    gitDir, (System.nanoTime() - startedNanos) / 1_000_000);
            configured.remove(cacheKey(gitDir));
        }
        String key = cacheKey(gitDir);
        if (!configured.contains(key)) {
            configure(gitDir);
            configured.add(key);
        }
        writeExcludeFile(gitDir);
        writeGitFile(workspaceRoot, gitDir);
    }

    /**
     * 在工作区根写 {@code .git} gitfile，指向 {@code .labex/git-snapshots}。
     *
     * <p>为什么需要：裸仓库没有工作树，{@code git log} 在工作区里找不到它，于是 git 会向上查找，
     * 撞上外层目录的仓库（用户把项目放在自己的仓库里时尤其明显），Agent 就会报出别人的历史。
     * gitfile 是 git 自己的机制：{@code .git} 文件内容为 {@code gitdir: <相对路径>}，
     * 从此 {@code git log} / {@code git status} 解析到的就是本项目的仓库。
     *
     * <p>仓库本身仍是非裸模式 + 工作树 = 工作区根：裸仓库下 git 拒绝执行需要工作树的命令
     * （{@code status} / {@code diff} / {@code add} 不带显式 {@code --work-tree} 时），
     * 而 Agent 会直接跑 {@code git status}。{@code core.worktree} 写相对路径，工作区搬迁后仍然有效。
     *
     * <p>上传项目自带的 {@code .git} 目录会被移到 {@code .labex/imported-git/<时间戳>/}：
     * 一个项目只能有一个仓库，两套历史并存时面板和 Agent 会各说各话。移动而不是删除，
     * 内容留在保护区里，需要时可以取回。
     */
    private void writeGitFile(Path workspaceRoot, Path gitDir) throws IOException {
        Path gitFile = workspaceRoot.resolve(GIT_FILE_NAME);
        if (Files.isDirectory(gitFile, LinkOption.NOFOLLOW_LINKS)) {
            Path archive = archiveImportedGit(workspaceRoot, gitFile);
            log.warn("GIT_IMPORTED_REPOSITORY_ARCHIVED workspace={} archivedTo={}", workspaceRoot, archive);
        }
        String content = "gitdir: " + relativeGitDir(workspaceRoot, gitDir) + "\n";
        if (Files.isRegularFile(gitFile, LinkOption.NOFOLLOW_LINKS)
                && content.equals(Files.readString(gitFile, StandardCharsets.UTF_8))) {
            return;
        }
        Files.writeString(gitFile, content, StandardCharsets.UTF_8);
    }

    /** 把上传项目自带的 {@code .git} 移到保护区，返回归档路径。同名时追加序号，不覆盖已有归档。 */
    private Path archiveImportedGit(Path workspaceRoot, Path importedGit) throws IOException {
        Path archiveRoot = workspaceRoot.resolve(".labex").resolve("imported-git");
        Files.createDirectories(archiveRoot);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(LocalDateTime.now(ZoneOffset.UTC));
        Path target = archiveRoot.resolve(stamp);
        for (int suffix = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); suffix++) {
            target = archiveRoot.resolve(stamp + "-" + suffix);
        }
        try {
            return Files.move(importedGit, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            return Files.move(importedGit, target);
        }
    }

    /** 仓库相对工作区根的路径，正斜杠形式；git 在 Windows 上也接受。 */
    private String relativeGitDir(Path workspaceRoot, Path gitDir) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path dir = gitDir.toAbsolutePath().normalize();
        if (!dir.startsWith(root)) {
            return dir.toString().replace('\\', '/');
        }
        return root.relativize(dir).toString().replace('\\', '/');
    }

    private String cacheKey(Path gitDir) {
        return gitDir.toAbsolutePath().normalize().toString();
    }

    private void configure(Path gitDir) throws IOException, InterruptedException {
        String existing = localConfig(gitDir);
        for (Map.Entry<String, String> entry : REQUIRED_CONFIG.entrySet()) {
            if (!existing.contains(entry.getKey() + "=" + entry.getValue())) {
                git.git(gitDir, gitDir, 20, List.of("config", "--local", entry.getKey(), entry.getValue()));
            }
        }
    }

    /** 一次子进程读出全部本地配置，避免每个键单独跑一次 git。 */
    private String localConfig(Path gitDir) throws IOException, InterruptedException {
        try {
            return git.git(gitDir, gitDir, 20, List.of("config", "--local", "--list")).output();
        } catch (IOException empty) {
            return "";
        }
    }

    /**
     * 把统一排除名单写进 {@code info/exclude}。内容与目标一致时不写盘，避免每次快照都产生 IO。
     */
    void writeExcludeFile(Path gitDir) throws IOException {
        Path exclude = gitDir.resolve("info").resolve("exclude");
        Files.createDirectories(exclude.getParent());
        String content = String.join("\n", ProjectScanPolicy.gitExcludeLines()) + "\n";
        if (Files.isRegularFile(exclude)) {
            String existing = Files.readString(exclude, StandardCharsets.UTF_8);
            if (existing.equals(content)) {
                return;
            }
        }
        Files.writeString(exclude, content, StandardCharsets.UTF_8);
        log.info("GIT_REPOSITORY_EXCLUDE_WRITTEN gitDir={} rules={}", gitDir, ProjectScanPolicy.gitExcludeLines().size());
    }

    /** 仓库是否已经有过提交。没有提交时 {@code status} 没有基线可比。 */
    public boolean hasCommits(Path gitDir) {
        try {
            git.git(gitDir, gitDir, 20, List.of("rev-parse", "--verify", "HEAD"));
            return true;
        } catch (IOException | InterruptedException missing) {
            if (missing instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
