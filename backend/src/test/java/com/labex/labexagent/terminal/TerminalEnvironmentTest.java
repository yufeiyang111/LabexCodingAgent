package com.labex.labexagent.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalEnvironmentTest {
    @Test
    void includesOnlyRequiredRuntimeVariablesAndDoesNotLeakHostSecrets() {
        Map<String, String> environment = TerminalEnvironment.forWorkspace(
                Path.of("D:/workspaces/project"),
                90,
                40,
                Map.of(
                        "PATH", "C:/Windows/System32",
                        "LABEX_AGENT_JWT_SECRET", "sensitive",
                        "MINIMAX_API_KEY", "sensitive",
                        "GITHUB_TOKEN", "sensitive"));

        assertThat(environment)
                .containsEntry("PATH", "C:/Windows/System32")
                .containsEntry("COLUMNS", "90")
                .containsEntry("LINES", "40")
                .containsEntry("HOME", "D:\\workspaces\\project\\.labex-agent\\runtime\\home")
                .containsEntry("USERPROFILE", "D:\\workspaces\\project\\.labex-agent\\runtime\\home")
                .containsEntry("APPDATA", "D:\\workspaces\\project\\.labex-agent\\runtime\\appdata")
                .containsEntry("LOCALAPPDATA", "D:\\workspaces\\project\\.labex-agent\\runtime\\localappdata")
                .containsEntry("NPM_CONFIG_CACHE", "D:\\workspaces\\project\\.labex-agent\\runtime\\npm-cache")
                .doesNotContainKeys("LABEX_AGENT_JWT_SECRET", "MINIMAX_API_KEY", "GITHUB_TOKEN");
    }
}
