package com.labex.labexagent.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.service.ProjectScanPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 排除规则的唯一事实源：{@code info/exclude} 必须与 Agent 扫描名单逐条一致。
 *
 * <p>这份断言的意义在于防止名单漂移：三处排除逻辑（扫描、同步、快照）曾各自维护一份，
 * 结果是 node_modules 进了版本库、10 万文件的项目无法 clone。名单只能有一个来源。
 */
class ProjectGitExcludeTest {

    @TempDir
    Path workspace;

    @Test
    void excludeFileMatchesTheScanPolicyDirectoryList() throws Exception {
        GitCommandRunner git = new GitCommandRunner();
        ProjectGitRepository repository = new ProjectGitRepository(git);
        Path gitDir = repository.gitDir(workspace);
        repository.ensureInitialized(workspace, gitDir);

        List<String> lines = Files.readAllLines(gitDir.resolve("info").resolve("exclude"), StandardCharsets.UTF_8);

        assertThat(lines).isEqualTo(ProjectScanPolicy.gitExcludeLines());
        assertThat(lines).contains("node_modules/", "npm-cache/", "dist/", ".labex/");
        assertThat(lines).allSatisfy(line -> assertThat(line).endsWith("/"));
    }

    @Test
    void excludeFileIsRewrittenOnlyWhenThePolicyChanges() throws Exception {
        GitCommandRunner git = new GitCommandRunner();
        ProjectGitRepository repository = new ProjectGitRepository(git);
        Path gitDir = repository.gitDir(workspace);
        repository.ensureInitialized(workspace, gitDir);
        Path exclude = gitDir.resolve("info").resolve("exclude");
        var firstWrite = Files.getLastModifiedTime(exclude);

        repository.ensureInitialized(workspace, gitDir);

        assertThat(Files.getLastModifiedTime(exclude)).isEqualTo(firstWrite);
    }

    @Test
    void repositoryLivesInsideAnIgnoredDirectorySoItNeverCommitsItself() throws Exception {
        GitCommandRunner git = new GitCommandRunner();
        ProjectGitRepository repository = new ProjectGitRepository(git);
        Path gitDir = repository.gitDir(workspace);
        repository.ensureInitialized(workspace, gitDir);

        // .labex/ 在排除名单里，仓库自己就在其中：这是刻意的，否则仓库会把自己提交进去。
        assertThat(ProjectScanPolicy.isIgnoredRelativePath(".labex/git-snapshots/HEAD")).isTrue();
        assertThat(gitDir).exists();
    }
}
