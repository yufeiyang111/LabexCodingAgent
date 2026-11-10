package com.labex.labexagent.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 工作区里的 {@code .git} gitfile：让 {@code git log} 解析到本项目的仓库。
 *
 * <p>背景：平台仓库是 {@code .labex/git-snapshots} 下的裸仓库，没有工作树。工作区里没有
 * {@code .git} 时，git 会向上逐级查找，撞上外层目录的仓库。用户把项目放在自己的仓库里
 * （或者上传的压缩包自带 {@code .git}）时，Agent 跑 {@code git log} 看到的就不是本项目的历史。
 * 这里用「外层仓库 + 内层工作区」的目录布局复现那个场景。
 */
class ProjectGitFileTest {

    @TempDir
    Path outerRepo;

    private final GitCommandRunner git = new GitCommandRunner();
    private final ProjectGitRepository repository = new ProjectGitRepository(git);

    @Test
    void gitLogInTheWorkspaceResolvesToTheProjectRepositoryNotAnAncestor() throws Exception {
        Path workspace = Files.createDirectory(outerRepo.resolve("workspace"));
        commitOuterRepository("outer history that must never surface");
        write(workspace, "src/Main.java", "class Main {}\n");

        Path gitDir = repository.gitDir(workspace);
        repository.ensureInitialized(workspace, gitDir);
        commitProjectFile(workspace, gitDir, "第一次提交");

        // 不带任何 --git-dir：模拟 Agent 在工作区里直接跑 git log。
        String log = runGit(workspace, "log", "--format=%s");

        assertThat(log).contains("第一次提交");
        assertThat(log).doesNotContain("outer history that must never surface");
        assertThat(Files.readString(workspace.resolve(".git"), StandardCharsets.UTF_8))
                .isEqualTo("gitdir: .labex/git-snapshots\n");
    }

    @Test
    void gitStatusInTheWorkspaceReportsTheProjectWorkingTree() throws Exception {
        Path workspace = Files.createDirectory(outerRepo.resolve("workspace"));
        Path gitDir = repository.gitDir(workspace);
        repository.ensureInitialized(workspace, gitDir);
        write(workspace, "README.md", "# project\n");
        commitProjectFile(workspace, gitDir, "initial");

        write(workspace, "README.md", "# project changed\n");

        // 裸仓库没有工作树，git status 会直接失败；core.bare=false + core.worktree 之后才可用。
        assertThat(runGit(workspace, "status", "--porcelain")).contains("M README.md");
    }

    @Test
    void theGitFileItselfIsNeverCommitted() throws Exception {
        Path workspace = Files.createDirectory(outerRepo.resolve("workspace"));
        Path gitDir = repository.gitDir(workspace);
        repository.ensureInitialized(workspace, gitDir);
        write(workspace, "a.txt", "a\n");
        commitProjectFile(workspace, gitDir, "initial");

        assertThat(runGit(workspace, "ls-files")).doesNotContain(".git");
    }

    @Test
    void ensureInitializedIsIdempotentAndKeepsTheGitFileStable() throws Exception {
        Path workspace = Files.createDirectory(outerRepo.resolve("workspace"));
        Path gitDir = repository.gitDir(workspace);

        repository.ensureInitialized(workspace, gitDir);
        var firstWrite = Files.getLastModifiedTime(workspace.resolve(".git"));
        repository.ensureInitialized(workspace, gitDir);

        assertThat(Files.getLastModifiedTime(workspace.resolve(".git"))).isEqualTo(firstWrite);
    }

    @Test
    void anUploadedRepositoryIsArchivedInsteadOfShadowingTheProjectOne() throws Exception {
        Path workspace = Files.createDirectory(outerRepo.resolve("workspace"));
        // 模拟「上传的压缩包自带 .git」：一个独立的历史。
        runGit(workspace, "init", "--initial-branch=main");
        write(workspace, "uploaded.txt", "from the archive\n");
        runGit(workspace, "add", "-A");
        runGit(workspace, "-c", "user.name=uploader", "-c", "user.email=u@local",
                "commit", "-m", "uploaded history");

        Path gitDir = repository.gitDir(workspace);
        repository.ensureInitialized(workspace, gitDir);

        assertThat(Files.isDirectory(workspace.resolve(".git"))).isFalse();
        assertThat(Files.readString(workspace.resolve(".git"), StandardCharsets.UTF_8))
                .isEqualTo("gitdir: .labex/git-snapshots\n");
        // 归档保留内容，不删除：上传方的历史仍可取回。
        try (var archived = Files.list(workspace.resolve(".labex/imported-git"))) {
            Path kept = archived.findFirst().orElseThrow();
            assertThat(runGit(kept, "log", "--format=%s")).contains("uploaded history");
        }
    }

    /** 外层仓库：工作区就在它里面，未加 gitfile 时 {@code git log} 会找到这里。 */
    private void commitOuterRepository(String message) throws Exception {
        runGit(outerRepo, "init", "--initial-branch=main");
        write(outerRepo, "outer.txt", "outer\n");
        runGit(outerRepo, "add", "-A");
        runGit(outerRepo, "-c", "user.name=outer", "-c", "user.email=o@local", "commit", "-m", message);
    }

    private void commitProjectFile(Path workspace, Path gitDir, String message) throws Exception {
        git.git(workspace, gitDir, 60, List.of("add", "--all", "--", "."));
        git.git(workspace, gitDir, 60, List.of("commit", "-m", message));
    }

    private String runGit(Path directory, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        return new GitCommandRunner().run(directory, command, 60).output();
    }

    private void write(Path root, String relativePath, String content) throws Exception {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
