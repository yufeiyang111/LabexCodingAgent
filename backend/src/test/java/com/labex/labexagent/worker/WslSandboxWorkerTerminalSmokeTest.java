package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.LocalProcessExecutor;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in acceptance test for an interactive terminal inside the WSL bubblewrap sandbox. */
@EnabledIfSystemProperty(named = "labex.wsl.smoke", matches = ".+")
class WslSandboxWorkerTerminalSmokeTest {

    @TempDir
    Path workspace;

    @Test
    void streamsInteractiveCommandsWithoutExposingHostDrives() throws Exception {
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-terminal-smoke", workspace);
        StringBuilder output = new StringBuilder();
        CountDownLatch marker = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Integer> exitCode = new AtomicReference<>();
        SandboxWorker.InteractiveTerminal terminal = worker.openTerminal(run, new SandboxWorker.TerminalSpec(
                "wsl-terminal-smoke", workspace, 120, 30,
                data -> {
                    synchronized (output) {
                        output.append(data);
                        if (output.indexOf("terminal-sandbox-ok") >= 0) {
                            marker.countDown();
                        }
                    }
                }, code -> {
                    exitCode.set(code);
                    closed.countDown();
                }));

        try {
            terminal.writeInput("test ! -e /mnt/c && test ! -e /mnt/d && test ! -e /proc/1/root/mnt/c && test ! -e /proc/1/root/mnt/d && printf 'terminal-sandbox-ok\n'\n");
            assertTrue(marker.await(20, TimeUnit.SECONDS), () -> "terminal output: " + output);
        } finally {
            terminal.terminate();
            assertTrue(closed.await(10, TimeUnit.SECONDS), () -> "terminal close callback was not received; output: " + output);
            Thread.sleep(500);
        }
    }
}
