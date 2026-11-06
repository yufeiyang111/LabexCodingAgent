package com.labex.labexagent.git;

import com.labex.labexagent.service.ProjectScanPolicy;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    static final String COMMITTER_NAME = "Labex Agent";
    static final String COMMITTER_EMAIL = "labex-agent@local";
    /** 仓库本地配置：换机器/新克隆都要一致，且不能让工作区的 .gitattributes 改变行为。 */
    private static final Map<String, String> REQUIRED_CONFIG = Map.of(
            "core.autocrlf", "false",
            "core.longpaths", "true",
            "core.symlinks", "true",
            "core.quotepath", "false",
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
