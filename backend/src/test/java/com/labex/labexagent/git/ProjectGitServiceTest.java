package com.labex.labexagent.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.WorkspaceLeaseService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 用户 git 提交的端到端行为：未提交改动、暂存、提交、历史、回滚。
 *
 * <p>回滚语义是本类重点：回滚必须新增一条 Revert 提交，让历史保持完整、回滚本身可撤销。
 */
class ProjectGitServiceTest {

    @TempDir
    Path workspace;

    private StudentProject project;
    private ProjectGitService service;

    @BeforeEach
    void setUp() {
        project = new StudentProject();
        project.setProjectId(91);
        project.setWorkspacePath(workspace.toString());
        GitCommandRunner git = new GitCommandRunner();
        service = new ProjectGitService(git, new ProjectGitRepository(git),
                new ProjectGitProperties(), new WorkspaceLeaseService());
    }

    @Test
    void reportsUnstagedAndStagedChangesSeparately() throws Exception {
        write("src/Main.java", "class Main {}\n");
        write("README.md", "# project\n");
        service.stage(project, List.of());
        service.commit(project, "initial");

        write("src/Main.java", "class Main { int value; }\n");
        write("src/Added.java", "class Added {}\n");
        service.stage(project, List.of("src/Main.java"));

        ProjectGitDtos.StatusView status = service.status(project);

        assertThat(status.initialized()).isTrue();
        assertThat(status.stagedCount()).isEqualTo(1);
        assertThat(status.unstagedCount()).isEqualTo(1);
        assertThat(status.entries())
                .anySatisfy(entry -> {
                    assertThat(entry.path()).isEqualTo("src/Main.java");
                    assertThat(entry.status()).isEqualTo("M");
                    assertThat(entry.staged()).isTrue();
                })
                .anySatisfy(entry -> {
                    assertThat(entry.path()).isEqualTo("src/Added.java");
                    assertThat(entry.status()).isEqualTo("??");
                    assertThat(entry.staged()).isFalse();
                });
    }

    @Test
    void commitHistoryIsNewestFirstAndPaged() throws Exception {
        write("a.txt", "one\n");
        service.stage(project, List.of());
        service.commit(project, "first");
        write("a.txt", "two\n");
        service.stage(project, List.of());
        service.commit(project, "second");

        ProjectGitDtos.CommitPage page = service.commits(project, null, 1);

        assertThat(page.commits()).hasSize(1);
        assertThat(page.commits().get(0).message()).isEqualTo("second");
        assertThat(page.nextBefore()).isEqualTo(page.commits().get(0).sha());

        ProjectGitDtos.CommitPage next = service.commits(project, page.nextBefore(), 1);
        assertThat(next.commits()).hasSize(1);
        assertThat(next.commits().get(0).message()).isEqualTo("first");
        assertThat(next.nextBefore()).isNull();
    }

    @Test
    void commitHistoryIsEmptyBeforeTheFirstCommit() throws Exception {
        write("src/Main.java", "class Main {}\n");
        // 首次打开源码管理面板就会走到 status：它建库，但没有提交。
        service.status(project);

        ProjectGitDtos.CommitPage page = service.commits(project, null, 10);

        assertThat(page.commits()).isEmpty();
        assertThat(page.nextBefore()).isNull();
    }

    @Test
    void commitDetailListsFilesWithLineCounts() throws Exception {
        write("src/Main.java", "class Main {}\n");
        service.stage(project, List.of());
        service.commit(project, "initial");
        write("src/Main.java", "class Main {}\nclass Extra {}\n");
        service.stage(project, List.of());
        ProjectGitDtos.CommitResult result = service.commit(project, "add Extra");

        ProjectGitDtos.CommitDetail detail = service.commitDetail(project, result.sha());

        assertThat(detail.commit().message()).isEqualTo("add Extra");
        assertThat(detail.files()).hasSize(1);
        assertThat(detail.files().get(0).path()).isEqualTo("src/Main.java");
        assertThat(detail.files().get(0).status()).isEqualTo("M");
        assertThat(detail.files().get(0).additions()).isEqualTo(1);
        assertThat(detail.files().get(0).deletions()).isZero();
    }

    @Test
    void revertAddsANewCommitInsteadOfRewritingHistory() throws Exception {
        write("a.txt", "one\n");
        service.stage(project, List.of());
        String first = service.commit(project, "first").sha();
        write("a.txt", "two\n");
        service.stage(project, List.of());
        service.commit(project, "second");

        ProjectGitDtos.CommitResult reverted = service.revert(project, first);

        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("one\n");
        assertThat(reverted.message()).isEqualTo("Revert \"first\"");
        ProjectGitDtos.CommitPage history = service.commits(project, null, 10);
        assertThat(history.commits()).hasSize(3);
        assertThat(history.commits().get(0).sha()).isEqualTo(reverted.sha());
        assertThat(history.commits().get(1).message()).isEqualTo("second");
        assertThat(history.commits().get(2).message()).isEqualTo("first");
    }

    @Test
    void revertRefusesWhenTheWorkspaceHasUncommittedChanges() throws Exception {
        write("a.txt", "one\n");
        service.stage(project, List.of());
        String first = service.commit(project, "first").sha();
        write("a.txt", "two\n");
        service.stage(project, List.of());
        service.commit(project, "second");
        write("a.txt", "work in progress\n");

        assertThatThrownBy(() -> service.revert(project, first))
                .isInstanceOf(ProjectGitService.ProjectGitException.class)
                .hasMessageContaining("未提交改动");
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("work in progress\n");
    }

    @Test
    void revertRefusesWhenAlreadyAtTheTargetCommit() throws Exception {
        write("a.txt", "one\n");
        service.stage(project, List.of());
        String first = service.commit(project, "first").sha();

        assertThatThrownBy(() -> service.revert(project, first))
                .isInstanceOf(ProjectGitService.ProjectGitException.class)
                .hasMessageContaining("无需回滚");
    }

    @Test
    void commitRefusesWithoutStagedChanges() throws Exception {
        write("a.txt", "one\n");

        assertThatThrownBy(() -> service.commit(project, "empty"))
                .isInstanceOf(ProjectGitService.ProjectGitException.class)
                .hasMessageContaining("没有已暂存的改动");
    }

    @Test
    void commitRefusesBlankMessage() {
        assertThatThrownBy(() -> service.commit(project, "   "))
                .isInstanceOf(ProjectGitService.ProjectGitException.class)
                .hasMessageContaining("提交信息不能为空");
    }

    @Test
    void statusListsFilesBeforeTheFirstCommit() throws Exception {
        write("src/Main.java", "class Main {}\n");

        ProjectGitDtos.StatusView status = service.status(project);

        assertThat(status.initialized()).isFalse();
        assertThat(status.entries()).extracting(ProjectGitDtos.StatusEntry::path).contains("src/Main.java");
        assertThat(status.entries()).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo("??");
            assertThat(entry.staged()).isFalse();
        });
    }

    @Test
    void statusIgnoresPathsOutsideTheVersionControlScope() throws Exception {
        write("node_modules/pkg/index.js", "module.exports = 1;\n");
        write("src/Main.java", "class Main {}\n");

        ProjectGitDtos.StatusView status = service.status(project);

        assertThat(status.entries()).extracting(ProjectGitDtos.StatusEntry::path)
                .contains("src/Main.java")
                .doesNotContain("node_modules/pkg/index.js");
    }

    @Test
    void stageRejectsEscapingPaths() {
        assertThatThrownBy(() -> service.stage(project, List.of("../outside.txt")))
                .isInstanceOf(ProjectGitService.ProjectGitException.class)
                .hasMessageContaining("工作区相对路径");
    }

    @Test
    void commitMessageWithQuotesAndColonsSurvivesIntact() throws Exception {
        // Windows 上 ProcessBuilder 对含内嵌双引号的 -m 参数转义不完整，
        // 会把 Revert "abc" 拆成多个 argv，git 把后半段当 pathspec 报错。
        write("a.txt", "one\n");
        service.stage(project, List.of());
        String message = "Revert \"Initial import: blog project sources\"";
        ProjectGitDtos.CommitResult result = service.commit(project, message);

        assertThat(result.message()).isEqualTo(message);
        assertThat(service.commits(project, null, 5).commits().get(0).message()).isEqualTo(message);
    }

    @Test
    void commitMessageWithMultipleLinesIsPreserved() throws Exception {
        write("a.txt", "one\n");
        service.stage(project, List.of());
        String message = "feat: add file\n\nBody line with \"quotes\" and: colons.";

        service.commit(project, message);

        assertThat(service.commits(project, null, 5).commits().get(0).message()).isEqualTo("feat: add file");
        ProjectGitDtos.CommitDetail detail = service.commitDetail(project,
                service.commits(project, null, 5).commits().get(0).sha());
        assertThat(detail.commit().message()).isEqualTo("feat: add file");
    }

    @Test
    void workingDiffShowsUncommittedChanges() throws Exception {
        write("a.txt", "one\n");
        service.stage(project, List.of());
        service.commit(project, "first");
        write("a.txt", "one\ntwo\n");

        String diff = service.workingDiff(project, "a.txt");

        assertThat(diff).contains("+two");
    }

    @Test
    void workingDiffShowsTheWholeFileBeforeTheFirstCommit() throws Exception {
        write("src/Main.java", "class Main {}\n");
        service.status(project);

        String diff = service.workingDiff(project, "src/Main.java");

        assertThat(diff).contains("new file mode").contains("+class Main {}");
    }

    @Test
    void workingDiffShowsUntrackedFileAsAddedAfterTheFirstCommit() throws Exception {
        write("a.txt", "one\n");
        service.stage(project, List.of());
        service.commit(project, "first");
        write("b.txt", "brand new\n");

        String diff = service.workingDiff(project, "b.txt");

        assertThat(diff).contains("new file mode").contains("+brand new");
    }

    @Test
    void workingDiffShowsBinaryFileAsASingleNotice() throws Exception {
        Files.write(workspace.resolve("data.bin"), new byte[] {0, 1, 2, 3, 0, 5});
        service.status(project);

        String diff = service.workingDiff(project, "data.bin");

        assertThat(diff).contains("Binary files").doesNotContain("GIT binary patch");
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = workspace.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
