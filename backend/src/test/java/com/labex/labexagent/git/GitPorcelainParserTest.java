package com.labex.labexagent.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * porcelain / name-status / numstat 输出解析。
 *
 * <p>这些格式的坑都在细节里：NUL 分隔、重命名条目多带一个路径、状态字母两列语义不同、
 * 二进制文件用 {@code -} 代替行数。用真实 git 输出做样本，避免自造格式自测通过。
 */
class GitPorcelainParserTest {

    @Test
    void parsesModifiedAddedAndDeletedEntries() {
        String output = " M src/Main.java\u0000"
                + "A  src/Added.java\u0000"
                + " D src/Gone.java\u0000";

        List<ProjectGitDtos.StatusEntry> entries = GitPorcelainParser.parseStatus(output, 100);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).path()).isEqualTo("src/Main.java");
        assertThat(entries.get(0).status()).isEqualTo("M");
        assertThat(entries.get(0).staged()).isFalse();
        assertThat(entries.get(1).status()).isEqualTo("A");
        assertThat(entries.get(1).staged()).isTrue();
        assertThat(entries.get(2).status()).isEqualTo("D");
        assertThat(entries.get(2).staged()).isFalse();
    }

    @Test
    void parsesUntrackedEntries() {
        List<ProjectGitDtos.StatusEntry> entries =
                GitPorcelainParser.parseStatus("?? new-file.txt\u0000", 100);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("??");
        assertThat(entries.get(0).staged()).isFalse();
    }

    @Test
    void parsesRenameEntriesThatCarryASecondPath() {
        // git 在重命名条目里追加一个 NUL 分隔的旧路径
        List<ProjectGitDtos.StatusEntry> entries =
                GitPorcelainParser.parseStatus("R  src/New.java\u0000src/Old.java\u0000", 100);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path()).isEqualTo("src/New.java");
        assertThat(entries.get(0).oldPath()).isEqualTo("src/Old.java");
        assertThat(entries.get(0).status()).isEqualTo("R");
    }

    @Test
    void keepsPathsWithSpacesIntact() {
        List<ProjectGitDtos.StatusEntry> entries =
                GitPorcelainParser.parseStatus(" M docs/user guide.md\u0000", 100);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path()).isEqualTo("docs/user guide.md");
    }

    @Test
    void honoursTheEntryLimit() {
        String output = "?? a.txt\u0000?? b.txt\u0000?? c.txt\u0000";

        assertThat(GitPorcelainParser.parseStatus(output, 2)).hasSize(2);
    }

    @Test
    void parsesNameStatusForPlainAndRenamedFiles() {
        String output = "M\u0000src/Main.java\u0000R100\u0000src/Old.java\u0000src/New.java\u0000D\u0000src/Gone.java\u0000";

        List<ProjectGitDtos.CommitFile> files = GitPorcelainParser.parseNameStatus(output);

        assertThat(files).hasSize(3);
        assertThat(files.get(0).path()).isEqualTo("src/Main.java");
        assertThat(files.get(0).status()).isEqualTo("M");
        assertThat(files.get(1).path()).isEqualTo("src/New.java");
        assertThat(files.get(1).oldPath()).isEqualTo("src/Old.java");
        assertThat(files.get(1).status()).isEqualTo("R");
        assertThat(files.get(2).status()).isEqualTo("D");
    }

    @Test
    void appliesLineCountsAndMarksBinaryFiles() {
        List<ProjectGitDtos.CommitFile> files = new ArrayList<>(List.of(
                new ProjectGitDtos.CommitFile("src/Main.java", "", "M", 0, 0, false),
                new ProjectGitDtos.CommitFile("assets/logo.png", "", "A", 0, 0, false)));
        String numstat = "12\t3\tsrc/Main.java\u0000-\t-\tassets/logo.png\u0000";

        GitPorcelainParser.applyNumstat(numstat, files);

        assertThat(files.get(0).additions()).isEqualTo(12);
        assertThat(files.get(0).deletions()).isEqualTo(3);
        assertThat(files.get(0).binary()).isFalse();
        assertThat(files.get(1).binary()).isTrue();
        assertThat(files.get(1).additions()).isZero();
    }

    @Test
    void emptyOutputYieldsNoEntries() {
        assertThat(GitPorcelainParser.parseStatus("", 100)).isEmpty();
        assertThat(GitPorcelainParser.parseNameStatus("")).isEmpty();
    }

    /**
     * {@code --branch} 会把分支头放在第一个 NUL 分段。它不是文件条目，
     * 若不跳过会被当成路径为 {@code "ster"} 之类的脏数据塞进列表。
     */
    @Test
    void skipsTheBranchHeaderWhenStatusRunsWithBranch() {
        String output = "## master\u0000"
                + " M src/Main.java\u0000"
                + "?? new-file.txt\u0000";

        List<ProjectGitDtos.StatusEntry> entries = GitPorcelainParser.parseStatus(output, 100);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path()).isEqualTo("src/Main.java");
        assertThat(entries.get(1).path()).isEqualTo("new-file.txt");
    }

    /**
     * 分支头同时携带「有没有提交」，因此不必再跑一次 {@code rev-parse --verify HEAD}。
     * 这是 status 从两个子进程压到一个的依据，行为必须和 rev-parse 的成败一致。
     */
    @Test
    void derivesInitializedFromTheBranchHeader() {
        assertThat(GitPorcelainParser.hasCommitsFromStatus("## master\u0000 M a.txt\u0000")).isTrue();
        assertThat(GitPorcelainParser.hasCommitsFromStatus("## master...origin/master\u0000")).isTrue();
        assertThat(GitPorcelainParser.hasCommitsFromStatus(
                "## No commits yet on master\u0000?? a.txt\u0000")).isFalse();
        // 没有分支头（未带 --branch）时返回 null，调用方回落到 rev-parse
        assertThat(GitPorcelainParser.hasCommitsFromStatus(" M a.txt\u0000")).isNull();
        assertThat(GitPorcelainParser.hasCommitsFromStatus("")).isNull();
    }

    /**
     * 没有提交时，分支头后面紧跟着未跟踪文件。跳过分支头之后必须一个不少地列出来，
     * 否则全新项目的第一次提交没有清单可选。
     */
    @Test
    void keepsUntrackedFilesWhenThereAreNoCommitsYet() {
        String output = "## No commits yet on master\u0000"
                + "?? src/Main.java\u0000"
                + "?? README.md\u0000";

        List<ProjectGitDtos.StatusEntry> entries = GitPorcelainParser.parseStatus(output, 100);

        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo("??");
            assertThat(entry.staged()).isFalse();
        });
        assertThat(GitPorcelainParser.hasCommitsFromStatus(output)).isFalse();
    }
}
