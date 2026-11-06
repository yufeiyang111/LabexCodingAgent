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
}
