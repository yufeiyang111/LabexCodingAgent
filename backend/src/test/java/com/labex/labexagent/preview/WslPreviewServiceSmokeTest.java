package com.labex.labexagent.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.worker.WslSandboxWorker;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real WSL+bwrap preview smoke test, enabled only on an explicit WSL-capable development host. */
@EnabledIfSystemProperty(named = "labex.wsl.preview.smoke", matches = "true")
class WslPreviewServiceSmokeTest {

    @TempDir
    Path workspace;

    @Test
    void keepsAWslWorkerPreviewReachableFromWindowsUntilExplicitlyStopped() throws Exception {
        int port = freePort();
        Files.writeString(workspace.resolve("index.html"), "wsl-preview-ready", StandardCharsets.UTF_8);
        PreviewRuntimeProperties properties = new PreviewRuntimeProperties();
        properties.setStartupTimeoutMs(10_000);
        properties.setPollIntervalMs(50);
        ProjectPreviewService service = new ProjectPreviewService(
                new WslSandboxWorker(new LocalProcessExecutor(), "Debian"),
                new AgentExecutionProperties(), properties);
        try {
            ProjectPreviewService.PreviewRun run = service.start(new ProjectPreviewService.StartRequest(
                    7, 12, 101L, workspace, ".",
                    "python3 -m http.server " + port + " --bind 0.0.0.0", port, "/"));

            assertEquals(ProjectPreviewService.Status.READY, run.status(), run::toString);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(run.publicUrl())).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("wsl-preview-ready", response.body());

            ProjectPreviewService.PreviewRun stopped = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> service.stop(7, 12, run.previewId()));
            assertEquals(ProjectPreviewService.Status.STOPPED, stopped.status());
        } finally {
            service.stopAll();
        }
    }

    @Test
    void fallsBackToPython3WhenTheWslWorkerHasNoPythonAlias() throws Exception {
        int port = freePort();
        Files.writeString(workspace.resolve("index.html"), "wsl-python-fallback-ready", StandardCharsets.UTF_8);
        PreviewRuntimeProperties properties = new PreviewRuntimeProperties();
        properties.setStartupTimeoutMs(10_000);
        properties.setPollIntervalMs(50);
        ProjectPreviewService service = new ProjectPreviewService(
                new WslSandboxWorker(new LocalProcessExecutor(), "Debian"),
                new AgentExecutionProperties(), properties);
        try {
            ProjectPreviewService.PreviewRun run = service.start(new ProjectPreviewService.StartRequest(
                    7, 12, 103L, workspace, ".",
                    "python -m http.server " + port + " --bind 0.0.0.0", port, "/"));

            assertEquals(ProjectPreviewService.Status.READY, run.status(), run::toString);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(run.publicUrl())).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("wsl-python-fallback-ready", response.body());
        } finally {
            service.stopAll();
        }
    }
    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
