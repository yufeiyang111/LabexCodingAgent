package com.labex.monitor.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.monitor.health.checker.WorkspaceStorageHealthChecker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceStorageHealthCheckerTest {

    @TempDir
    Path tempDir;

    private WorkspaceStorageHealthChecker checker(String root) {
        return new WorkspaceStorageHealthChecker(root);
    }

    @Test
    void writableDirectoryIsUp() {
        WorkspaceStorageHealthChecker checker = checker(tempDir.toString());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.name()).isEqualTo("workspace");
        assertThat(result.affectsCoreService()).isTrue();
    }

    @Test
    void probeFileIsCleanedUpAfterCheck() throws IOException {
        checker(tempDir.toString()).check();

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.noneMatch(p -> p.getFileName().toString().startsWith(".health-probe-"))).isTrue();
        }
    }

    @Test
    void missingDirectoryIsDown() {
        Path missing = tempDir.resolve("does-not-exist");
        WorkspaceStorageHealthChecker checker = checker(missing.toString());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.errorCode()).isEqualTo("MISSING");
    }

    @Test
    void fileInsteadOfDirectoryIsDown() throws IOException {
        Path file = tempDir.resolve("probe-file");
        Files.writeString(file, "x");
        WorkspaceStorageHealthChecker checker = checker(file.toString());

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.errorCode()).isEqualTo("INVALID");
    }

    @Test
    void safeMessageDoesNotLeakFullPath() {
        WorkspaceStorageHealthChecker checker = checker(tempDir.resolve("nope").toString());

        HealthCheckResult result = checker.check();

        assertThat(result.safeMessage()).doesNotContain(tempDir.toString());
        assertThat(result.safeMessage()).doesNotContain(":\\");
        assertThat(result.details()).doesNotContainKey("root");
    }
}
