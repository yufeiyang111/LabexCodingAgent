package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.runtime.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in acceptance test for the locally installed WSL bubblewrap sandbox. */
@EnabledIfSystemProperty(named = "labex.wsl.smoke", matches = ".+")
class WslSandboxWorkerSmokeTest {

    @TempDir
    Path workspace;

    @Test
    void executesWithOnlyTheWorkspaceBoundAndNoHostDriveVisible() throws Exception {
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-smoke", workspace);
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/bash", "-lc",
                        "test ! -e /mnt/c && test ! -e /mnt/d && test ! -e /proc/1/root/mnt/c && test ! -e /proc/1/root/mnt/d && mkdir -p capability-probe && ! mount -t tmpfs tmpfs capability-probe && node --version && git --version && python3 --version && java -version && mvn -v && command -v typescript-language-server && typescript-language-server --version && command -v vue-language-server && vue-language-server --version && command -v pyright-langserver && command -v jdtls && jdtls --help >/dev/null && printf workspace-only > result.txt && cat result.txt"),
                workspace, Duration.ofSeconds(30), 10_000);

        var result = worker.execute(run, request, CancellationToken.none());

        assertTrue(result.succeeded(), result::output);
        assertTrue(result.output().endsWith("workspace-only"), result::output);
        assertEquals("workspace-only", Files.readString(workspace.resolve("result.txt"), StandardCharsets.UTF_8));
    }
}
