package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in acceptance test for the locally installed WSL bubblewrap sandbox. */
@EnabledIfSystemProperty(named = "labex.wsl.smoke", matches = ".+")
class WslSandboxWorkerSmokeTest {

    @TempDir
    Path workspace;

    @Test
    void gracefullyCancelsOneCommandBeforeImmediatelyStartingTheNext() throws Exception {
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-cancellation-recovery", workspace);
        Files.writeString(workspace.resolve(".labex-acceptance-command-hold.cjs"), "setTimeout(() => {}, 40000);\n");
        ProcessExecutionRequest longRequest = new ProcessExecutionRequest(
                List.of("node", ".labex-acceptance-command-hold.cjs"),
                workspace, Duration.ofSeconds(40), 10_000);
        ControlledCancellationToken token = new ControlledCancellationToken();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<ProcessExecutionResult> first = executor.submit(() -> worker.execute(run, longRequest, token));
            Thread.sleep(750);
            assertTrue(!first.isDone(), "the node hold command exited before cancellation");
            token.cancel();
            ProcessExecutionResult cancelled = first.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.CANCELLED, cancelled.status(), cancelled::output);

            ProcessExecutionResult next = worker.execute(run, new ProcessExecutionRequest(
                    List.of("/bin/bash", "-lc", "printf second > second.marker"),
                    workspace, Duration.ofSeconds(15), 10_000), CancellationToken.none());
            assertTrue(next.succeeded(), next::output);
            assertEquals("second", Files.readString(workspace.resolve("second.marker"), StandardCharsets.UTF_8));
        } finally {
            executor.shutdownNow();
        }
    }

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

    private static final class ControlledCancellationToken implements CancellationToken {
        private final AtomicBoolean requested = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public boolean isCancellationRequested() {
            return requested.get();
        }

        @Override
        public Registration onCancellation(Runnable listener) {
            listeners.add(listener);
            if (requested.get()) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        private void cancel() {
            if (requested.compareAndSet(false, true)) {
                listeners.forEach(Runnable::run);
            }
        }
    }
}
