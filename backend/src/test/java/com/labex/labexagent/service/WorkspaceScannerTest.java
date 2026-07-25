package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScannerTest {
    @TempDir
    Path workspace;

    @Test
    void stopsAtTheCandidateFileBudget() throws Exception {
        write("src/First.java");
        write("src/Second.java");
        write("src/Third.java");

        WorkspaceScanner.ScanResult result = new WorkspaceScanner().scan(new SecureWorkspacePath(workspace),
                new WorkspaceScanner.ScanBudget(100, 2, 10, 5_000), () -> false, (file, attributes) -> true);

        assertThat(result.stopReason()).isEqualTo(WorkspaceScanner.StopReason.CANDIDATE_LIMIT);
        assertThat(result.candidateFiles()).isEqualTo(2);
        assertThat(result.visitedEntries()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void prunesIgnoredDirectoriesBeforeVisitingTheirFiles() throws Exception {
        write("src/App.java");
        write("node_modules/package/dependency.js");
        write("npm-cache/content/cache.json");

        WorkspaceScanner.ScanResult result = new WorkspaceScanner().scan(new SecureWorkspacePath(workspace),
                new WorkspaceScanner.ScanBudget(100, 100, 10, 5_000), () -> false, (file, attributes) -> true);

        assertThat(result.stopReason()).isEqualTo(WorkspaceScanner.StopReason.COMPLETED);
        assertThat(result.candidateFiles()).isEqualTo(1);
        assertThat(result.skippedDirectories()).isEqualTo(2);
    }

    private void write(String relativePath) throws Exception {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content", StandardCharsets.UTF_8);
    }


    @Test
    void appliesWorkspaceIgnoreFileOnlyToAgentScanning() throws Exception {
        write("src/App.java");
        write("logs/application.log");
        write("generated/client.js");
        Files.writeString(workspace.resolve(".labex-agentignore"), "# Agent-only scan exclusions\nlogs/\ngenerated/**\n", StandardCharsets.UTF_8);
        List<String> scanned = new ArrayList<>();

        WorkspaceScanner.ScanResult result = new WorkspaceScanner().scan(new SecureWorkspacePath(workspace),
                new WorkspaceScanner.ScanBudget(100, 100, 10, 5_000), () -> false,
                (file, attributes) -> {
                    scanned.add(workspace.relativize(file).toString().replace('\\', '/'));
                    return true;
                });

        assertThat(result.stopReason()).isEqualTo(WorkspaceScanner.StopReason.COMPLETED);
        assertThat(scanned).contains("src/App.java");
        assertThat(scanned).doesNotContain("logs/application.log", "generated/client.js");
    }

}
