package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DirectCommandWorkingDirectoryTest {

    @Test
    void rewritesSimpleCdPrefixIntoWorkingDirectory() {
        DirectCommandWorkingDirectory.Normalized normalized =
                DirectCommandWorkingDirectory.normalize("cd frontend && npm run build", ".");

        assertThat(normalized.command()).isEqualTo("npm run build");
        assertThat(normalized.workingDirectory()).isEqualTo("frontend");
        assertThat(normalized.rewritten()).isTrue();
    }

    @Test
    void combinesCdPrefixWithExplicitRelativeWorkingDirectory() {
        DirectCommandWorkingDirectory.Normalized normalized =
                DirectCommandWorkingDirectory.normalize("cd web && git status", "packages");

        assertThat(normalized.command()).isEqualTo("git status");
        assertThat(normalized.workingDirectory()).isEqualTo("packages/web");
    }

    @Test
    void refusesWorkspaceEscapingCdPrefix() {
        assertThatThrownBy(() -> DirectCommandWorkingDirectory.normalize(
                "cd ../outside && git status", "."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("working directory");
    }

    @Test
    void leavesOrdinaryDirectCommandUntouched() {
        DirectCommandWorkingDirectory.Normalized normalized =
                DirectCommandWorkingDirectory.normalize("git status", "frontend");

        assertThat(normalized.command()).isEqualTo("git status");
        assertThat(normalized.workingDirectory()).isEqualTo("frontend");
        assertThat(normalized.rewritten()).isFalse();
    }
}
