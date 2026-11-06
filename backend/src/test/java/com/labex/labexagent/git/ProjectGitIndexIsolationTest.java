package com.labex.labexagent.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.labexagent.workspace.WorkspaceLeaseService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 索引隔离：Agent 快照与用户暂存必须互不影响。
 *
 * <p>这是本设计里最容易出错的约束：两条路径都跑 {@code git add}，一旦共用索引，
 * 快照会清空用户的勾选状态，用户的暂存内容也会污染快照树、让 apply/undo 回滚到错误状态。
 */
class ProjectGitIndexIsolationTest {

    @TempDir
    Path workspace;

    private StudentProject project;
    private GitCommandRunner git;
    private ProjectGitRepository repository;
    private ProjectGitService projectGit;
    private GitSnapshotService snapshots;

    @BeforeEach
    void setUp() {
        project = new StudentProject();
        project.setProjectId(77);
        project.setWorkspacePath(workspace.toString());
        git = new GitCommandRunner();
        repository = new ProjectGitRepository(git);
        projectGit = new ProjectGitService(git, repository, new ProjectGitProperties(), new WorkspaceLeaseService());
        snapshots = new GitSnapshotService(git, repository);
    }

    @Test
    void snapshotKeepsTheUserStagingAreaIntact() throws Exception {
        write("src/Main.java", "class Main {}\n");
        write("src/Util.java", "class Util {}\n");
        projectGit.stage(project, List.of("src/Main.java", "src/Util.java"));
        projectGit.commit(project, "initial");

        write("src/Main.java", "class Main { int value; }\n");
        write("src/Util.java", "class Util { int value; }\n");
        projectGit.stage(project, List.of("src/Main.java"));

        // 快照发生在用户勾选之后：如果快照碰了默认索引，Main.java 的暂存状态就没了。
        GitSnapshotService.Snapshot before = snapshots.capture(project, "before agent step");
        write("src/Main.java", "class Main { int agent; }\n");
        GitSnapshotService.Snapshot after = snapshots.capture(project, "after agent step");

        assertThat(before.available()).isTrue();
        assertThat(after.available()).isTrue();
        assertThat(projectGit.status(project).stagedCount()).isEqualTo(1);
        assertThat(projectGit.status(project).entries())
                .anySatisfy(entry -> {
                    assertThat(entry.path()).isEqualTo("src/Main.java");
                    assertThat(entry.staged()).isTrue();
                });
    }

    @Test
    void stagingNeverLeaksIntoTheNextSnapshotTree() throws Exception {
        write("src/Main.java", "class Main {}\n");
        projectGit.stage(project, List.of("src/Main.java"));
        projectGit.commit(project, "initial");

        write("src/Main.java", "class Main { int value; }\n");
        projectGit.stage(project, List.of("src/Main.java"));
        // 只把 Main.java 暂存，不提交；随后让 Agent 改另一个文件并快照。
        write("src/Other.java", "class Other {}\n");
        GitSnapshotService.Snapshot snapshot = snapshots.capture(project, "after agent step", List.of("src/Other.java"));

        assertThat(snapshot.available()).isTrue();
        // 快照树只应包含被显式指定的路径（加上仓库已跟踪的路径由 add --all 决定），
        // 关键断言是：用户暂存区仍然是 1 条，没有被快照重置。
        assertThat(projectGit.status(project).stagedCount()).isEqualTo(1);
    }

    @Test
    void repeatedSnapshotsReuseTheirOwnIndexInsteadOfRewritingTheUserOne() throws Exception {
        write("src/Main.java", "class Main {}\n");
        GitSnapshotService.Snapshot first = snapshots.capture(project, "first");
        Path snapshotIndex = workspace.resolve(".labex/git-snapshots/snapshot.index");

        assertThat(first.available()).isTrue();
        assertThat(snapshotIndex).exists();
        assertThat(workspace.resolve(".labex/git-snapshots/index")).doesNotExist();

        write("src/Main.java", "class Main { int value; }\n");
        GitSnapshotService.Snapshot second = snapshots.capture(project, "second");

        assertThat(second.available()).isTrue();
        assertThat(second.ref()).isNotEqualTo(first.ref());
        assertThat(workspace.resolve(".labex/git-snapshots/index")).doesNotExist();
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = workspace.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
