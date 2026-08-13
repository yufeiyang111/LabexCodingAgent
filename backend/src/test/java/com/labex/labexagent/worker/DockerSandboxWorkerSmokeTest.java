package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.runtime.CancellationToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in acceptance check for the real Docker daemon. CI and ordinary unit-test runs remain daemon-free.
 *
 * <p>Run with {@code -Dlabex.docker.smoke.image=labex-agent-sandbox:smoke} and an explicitly controlled
 * workspace path. The test creates no files outside that workspace and proves the one-shot container is removed.
 */
@EnabledIfSystemProperty(named = "labex.docker.smoke.image", matches = ".+")
class DockerSandboxWorkerSmokeTest {
    private static final String RUN_ID = "p0-t4-smoke";

    @Test
    void executesInsideADisposableNetworklessContainerMountedOnlyToTheConfiguredWorkspace() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(workspace);
        Files.deleteIfExists(workspace.resolve("smoke-result.txt"));

        boolean wslMapping = Boolean.getBoolean("labex.docker.smoke.wsl-mapping");
        DockerSandboxWorker worker = new DockerSandboxWorker(
                new LocalProcessExecutor(),
                System.getProperty("labex.docker.smoke.image"),
                8, wslMapping, null);
        WorkerRunSpec run = new WorkerRunSpec(
                RUN_ID,
                workspace,
                new WorkerPolicy(System.getProperty("labex.docker.smoke.image"), 1_000, 1_024, 256, false));
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/sh", "-lc", "printf smoke-ok > smoke-result.txt && cat smoke-result.txt"),
                workspace,
                Duration.ofSeconds(30),
                10_000);

        List<String> command = worker.buildDockerCommand(run, request);
        assertOption(command, "--network", "none");
        String expectedSource = wslMapping ? wslPath(workspace.toString()) : workspace.toString();
        assertOption(command, "--mount", "type=bind,src=" + expectedSource + ",dst=/workspace");

        var result = worker.execute(run, request, CancellationToken.none());

        assertTrue(result.succeeded(), result::output);
        assertEquals(0, result.exitCode());
        assertEquals("smoke-ok", result.output());
        assertEquals("smoke-ok", Files.readString(workspace.resolve("smoke-result.txt"), StandardCharsets.UTF_8));
        String remainingContainers = listMatchingContainers();
        assertFalse(remainingContainers.contains("labex-agent-" + RUN_ID),
                () -> "worker left disposable container(s): " + remainingContainers);
    }

    private String wslPath(String windowsPath) {
        if (windowsPath.length() < 2 || windowsPath.charAt(1) != ':') {
            return windowsPath;
        }
        return "/mnt/" + Character.toLowerCase(windowsPath.charAt(0)) + windowsPath.substring(2).replace('\\', '/');
    }

    private Path workspace() {
        String configured = System.getProperty("labex.docker.smoke.workspace");
        Path workspace;
        if (configured == null || configured.isBlank()) {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            workspace = os.contains("win")
                    ? Path.of("D:/LabexAgent/.labex/docker-smoke-workspace")
                    : Path.of(System.getProperty("user.home"), ".labex", "docker-smoke-workspace");
        } else {
            workspace = Path.of(configured);
        }
        workspace = workspace.toAbsolutePath().normalize();
        final Path absoluteWorkspace = workspace;
        assertTrue(absoluteWorkspace.isAbsolute(), () -> "Docker smoke workspace must be absolute: " + absoluteWorkspace);
        return workspace;
    }

    private void assertOption(List<String> command, String option, String value) {
        int index = command.indexOf(option);
        assertTrue(index >= 0 && index + 1 < command.size(), () -> "missing option " + option + " in " + command);
        assertEquals(value, command.get(index + 1));
    }

    private String listMatchingContainers() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                        "docker", "ps", "-a", "--filter", "name=labex-agent-" + RUN_ID, "--format", "{{.Names}}")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output.trim();
    }
}