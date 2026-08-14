package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WslSandboxWorkerTest {

    @TempDir
    Path workspace;

    @Test
    void enablesNetworkOnlyForAnExplicitNetworkRunAndKeepsDnsReadOnly() {
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-network-contract", workspace, true);
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/bash", "-lc", "mvn test"), workspace, Duration.ofSeconds(30), 10_000);

        List<String> command = worker.buildWslCommand(run, request);

        assertTrue(!command.contains("--unshare-net"));
        assertContainsSequence(command, "--ro-bind", "/etc/resolv.conf", "/etc/resolv.conf");
        assertContainsSequence(command, "--ro-bind", "/etc/hosts", "/etc/hosts");
        assertContainsSequence(command, "--ro-bind", "/etc/ssl", "/etc/ssl");
        assertContainsSequence(command, "--bind", expectedWslPath(workspace), "/workspace");
    }

    @Test
    void buildsANetworklessBubblewrapCommandForAnExplicitOfflineRun() {
        WslSandboxWorker worker = new WslSandboxWorker(new LocalProcessExecutor(), "Debian");
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-contract", workspace, false);
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/bash", "-lc", "printf ok > result.txt"), workspace, Duration.ofSeconds(30), 10_000);

        List<String> command = worker.buildWslCommand(run, request);

        assertEquals("wsl.exe", command.get(0));
        assertTrue(command.contains("bwrap"));
        assertTrue(command.contains("--unshare-net"));
        assertTrue(command.contains("--unshare-pid"));
        assertContainsSequence(command, "--cap-drop", "ALL", "--ro-bind");
        assertContainsSequence(command, "--bind", expectedWslPath(workspace), "/workspace");
        assertContainsPair(command, "--chdir", "/workspace");
        assertContainsSequence(command, "--ro-bind", "/etc/alternatives", "/etc/alternatives");
        assertContainsSequence(command, "--ro-bind", "/etc/maven", "/etc/maven");
        assertContainsSequence(command, "--ro-bind", "/etc/java-21-openjdk", "/etc/java-21-openjdk");
        assertContainsPair(command, "--setenv", "HOME");
        assertTrue(command.contains("/workspace/.labex-agent/runtime/home"));
    }

    private String expectedWslPath(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return "/mnt/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
    }

    private void assertContainsSequence(List<String> command, String first, String second, String third) {
        for (int index = 0; index + 2 < command.size(); index++) {
            if (first.equals(command.get(index)) && second.equals(command.get(index + 1)) && third.equals(command.get(index + 2))) {
                return;
            }
        }
        throw new AssertionError("missing command sequence: " + first + " " + second + " " + third + " in " + command);
    }

    private void assertContainsPair(List<String> command, String option, String value) {
        for (int index = 0; index + 1 < command.size(); index++) {
            if (option.equals(command.get(index)) && value.equals(command.get(index + 1))) {
                return;
            }
        }
        throw new AssertionError("missing option pair: " + option + " " + value + " in " + command);
    }
}
