package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

class SandboxWorkerContractTest {

    @TempDir
    Path workspace;

    @Test
    void localDevelopmentWorkerPreparesExecutesAndManagesWorkspaceFiles() throws Exception {
        SandboxWorker worker = new LocalDevelopmentWorker(new LocalProcessExecutor());
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("contract-run", workspace);

        SandboxWorker.WorkspaceVersion prepared = worker.prepare(run);
        worker.applyChange(run, "artifacts/result.txt", "worker output");

        assertEquals(workspace.toAbsolutePath().normalize(), prepared.workspaceRoot());
        assertEquals("worker output", worker.readFile(run, "artifacts/result.txt"));
        assertEquals("artifacts/result.txt", worker.collectArtifacts(run, List.of("artifacts/result.txt")).get(0).relativePath());

        var result = worker.execute(run, new ProcessExecutionRequest(
                List.of(javaCommand(), "-version"),
                workspace,
                Duration.ofSeconds(10),
                10_000));

        assertTrue(result.succeeded());
        worker.terminate(run.runId());
    }

    @Test
    void dockerWorkerBuildsARestrictedNetworklessCommand() {
        DockerSandboxWorker worker = new DockerSandboxWorker(new LocalProcessExecutor());
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("docker-contract", workspace);
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/sh", "-lc", "echo ok"), workspace, Duration.ofSeconds(10), 10_000);

        List<String> command = worker.buildDockerCommand(run, request);

        assertContainsPair(command, "--network", "none");
        assertContainsPair(command, "--pids-limit", String.valueOf(run.policy().maxPids()));
        assertContainsPair(command, "--memory", run.policy().memoryMegabytes() + "m");
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--cap-drop"));
        assertFalse(command.contains("--privileged"));
        assertEquals(1, command.stream().filter("--mount"::equals).count());
        assertContainsPair(command, "--mount", "type=bind,src=" + workspace.toAbsolutePath().normalize() + ",dst=/workspace");
        assertContainsPair(command, "--env", "HOME=/workspace/.labex-agent/runtime/home");
        assertContainsPair(command, "--env", "NPM_CONFIG_CACHE=/workspace/.labex-agent/runtime/npm-cache");
    }

    @Test
    void dockerWorkerBuildsAnInteractiveTerminalInsideTheSameRestrictedWorkspace() {
        DockerSandboxWorker worker = new DockerSandboxWorker(new LocalProcessExecutor());
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("docker-terminal", workspace);
        SandboxWorker.TerminalSpec terminal = new SandboxWorker.TerminalSpec(
                "terminal-1", workspace, 120, 30, ignored -> { }, ignored -> { });

        List<String> command = worker.buildDockerTerminalCommand(run, terminal);

        assertTrue(command.contains("-i"));
        assertTrue(command.contains("-t"));
        assertContainsPair(command, "--network", "none");
        assertContainsPair(command, "--workdir", "/workspace");
        assertTrue(command.contains("--read-only"));
        assertContainsPair(command, "--env", "HOME=/workspace/.labex-agent/runtime/home");
        assertContainsPair(command, "--env", "NPM_CONFIG_CACHE=/workspace/.labex-agent/runtime/npm-cache");
    }

    @Test
    void localDevelopmentWorkerProvidesManagedBidirectionalProcessStreams() throws Exception {
        SandboxWorker worker = new LocalDevelopmentWorker(new LocalProcessExecutor());
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("stream-contract", workspace);

        try (SandboxWorker.WorkerProcess process = worker.startProcess(run, new ProcessExecutionRequest(
                List.of(javaCommand(), "-version"), workspace, Duration.ofSeconds(10), 10_000))) {
            assertTrue(process.processId() > 0);
            assertTrue(process.standardInput() != null);
            assertTrue(process.standardOutput() != null);
            assertTrue(process.standardError() != null);
        }
    }

    @Test
    void workerEnvironmentDoesNotInheritControlPlaneSecrets() {
        Map<String, String> environment = WorkerPolicy.defaults().safeEnvironment(workspace, Map.of(
                "PATH", "test-path",
                "LABEX_AGENT_JWT_SECRET", "must-not-reach-a-worker",
                "MINIMAX_API_KEY", "must-not-reach-a-worker"));

        assertEquals("test-path", environment.get("PATH"));
        assertTrue(environment.get("HOME").endsWith(".labex-agent" + java.io.File.separator + "runtime" + java.io.File.separator + "home"));
        assertEquals(environment.get("HOME"), environment.get("USERPROFILE"));
        assertTrue(environment.get("NPM_CONFIG_CACHE").contains(".labex-agent"));
        assertFalse(environment.containsKey("LABEX_AGENT_JWT_SECRET"));
        assertFalse(environment.containsKey("MINIMAX_API_KEY"));
    }

    @Test
    void productionDockerWorkerRequiresAnExplicitImageConfiguration() {
        Environment environment = org.mockito.Mockito.mock(Environment.class);
        org.mockito.Mockito.when(environment.matchesProfiles("prod")).thenReturn(true);
        DockerSandboxWorker worker = new DockerSandboxWorker(new LocalProcessExecutor(), "", environment);

        IllegalStateException error = assertThrows(IllegalStateException.class, worker::requireImageInProduction);

        assertTrue(error.getMessage().contains("LABEX_AGENT_WORKER_DOCKER_IMAGE"));
    }

    private String javaCommand() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
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
