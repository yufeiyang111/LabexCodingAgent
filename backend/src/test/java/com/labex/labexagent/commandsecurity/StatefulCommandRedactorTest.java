package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StatefulCommandRedactorTest {

    @Test
    void redactsSecretsSplitAcrossOutputChunks() {
        StatefulCommandRedactor redactor = new StatefulCommandRedactor();

        String first = redactor.append("prefix --token=split-se");
        String second = redactor.append("cret suffix");
        String finalChunk = redactor.finish();

        String publicOutput = first + second + finalChunk;
        assertThat(publicOutput).doesNotContain("split-secret").contains("<redacted>");
    }

    @Test
    void retainsOnlyBoundedSuffixUntilTheProcessCloses() {
        StatefulCommandRedactor redactor = new StatefulCommandRedactor();
        String largePrefix = "x".repeat(600);

        String emitted = redactor.append(largePrefix);

        assertThat(emitted).hasSize(88);
        assertThat(redactor.snapshotSuffix()).hasSize(512);
    }
}
