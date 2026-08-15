package com.labex.labexagent.preview;

import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.worker.LocalDevelopmentWorker;
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
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ProjectPreviewServiceTest {

    @TempDir
    Path workspace;

    @Test
    void exposesPreviewUrlOnlyAfterARealWorkerChildAnswersHttp() throws Exception {
        int port = freePort();
        Files.writeString(workspace.resolve("index.html"), "preview-ready", StandardCharsets.UTF_8);
        AgentExecutionProperties execution = previewExecution();
        PreviewRuntimeProperties properties = new PreviewRuntimeProperties();
        properties.setStartupTimeoutMs(5_000);
        properties.setPollIntervalMs(25);
        ProjectPreviewService service = new ProjectPreviewService(
                new LocalDevelopmentWorker(new LocalProcessExecutor()), execution, properties);
        try {
            ProjectPreviewService.PreviewRun run = service.start(new ProjectPreviewService.StartRequest(
                    7, 12, 99L, workspace, ".", testServerCommand(port), port, "/"));

            assertEquals(ProjectPreviewService.Status.READY, run.status(), run::toString);
            assertTrue(run.ready());
            assertEquals("http://localhost:" + port + "/", run.publicUrl());
            assertNotNull(run.processId());

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(run.publicUrl())).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("preview-ready", response.body());

            ProjectPreviewService.PreviewRun stopped = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> service.stop(7, 12, run.previewId()));
            assertEquals(ProjectPreviewService.Status.STOPPED, stopped.status());
            assertFalse(stopped.ready());
        } finally {
            service.stopAll();
        }
    }

    @Test
    void boundsPersistedPreviewOutputWhenARealServerWritesContinuously() throws Exception {
        int port = freePort();
        Files.writeString(workspace.resolve("noisy_preview_server.py"), """
                import http.server
                import sys
                print('x' * 61000, flush=True)
                http.server.ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1])), http.server.SimpleHTTPRequestHandler).serve_forever()
                """, StandardCharsets.UTF_8);
        PreviewRuntimeProperties properties = new PreviewRuntimeProperties();
        properties.setStartupTimeoutMs(5_000);
        properties.setPollIntervalMs(25);
        properties.setOutputMaxChars(1_024);
        ProjectPreviewService service = new ProjectPreviewService(
                new LocalDevelopmentWorker(new LocalProcessExecutor()), previewExecution(), properties);
        try {
            ProjectPreviewService.PreviewRun run = service.start(new ProjectPreviewService.StartRequest(
                    7, 12, 102L, workspace, ".", noisyServerCommand(port), port, "/"));

            assertEquals(ProjectPreviewService.Status.READY, run.status(), run::toString);
            String output = awaitOutput(workspace.resolve(run.outputPath()), 1_024);
            assertTrue(output.length() <= 1_300, () -> "preview output was not bounded: " + output.length());
            assertTrue(output.contains("[preview output truncated]"), () -> "preview truncation marker missing; length=" + output.length());
        } finally {
            service.stopAll();
        }
    }

    @Test
    void neverReturnsAUsableUrlWhenTheChildExitsBeforeReadiness() throws Exception {
        PreviewRuntimeProperties properties = new PreviewRuntimeProperties();
        properties.setStartupTimeoutMs(1_000);
        properties.setPollIntervalMs(25);
        ProjectPreviewService service = new ProjectPreviewService(
                new LocalDevelopmentWorker(new LocalProcessExecutor()), previewExecution(), properties);
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                int port = freePort();
                ProjectPreviewService.PreviewRun run = service.start(new ProjectPreviewService.StartRequest(
                        7, 12, 100L, workspace, ".", "java -version", port, "/"));

                assertEquals(ProjectPreviewService.Status.FAILED, run.status());
                assertFalse(run.ready());
                assertTrue(run.publicUrl().isBlank());
                assertTrue(run.failureCode().equals("process_exited") || run.failureCode().equals("readiness_timeout"), run::toString);
                assertFalse(run.failureHint().isBlank(), "failure result must include a safe diagnostic hint");
                String normalizedHint = run.failureHint().toLowerCase(java.util.Locale.ROOT);
                assertTrue(normalizedHint.contains("version") || normalizedHint.contains("no stdout/stderr was captured"),
                        run::failureHint);
            }
        } finally {
            service.stopAll();
        }
    }

    private AgentExecutionProperties previewExecution() {
        return new AgentExecutionProperties();
    }

    private String testServerCommand(int port) {
        return "python -m http.server " + port + " --bind 127.0.0.1";
    }

    private String noisyServerCommand(int port) {
        return "python noisy_preview_server.py " + port;
    }

    private String awaitOutput(Path outputPath, int expectedMinimum) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        String latest = "";
        while (System.nanoTime() < deadline) {
            if (Files.exists(outputPath)) {
                latest = Files.readString(outputPath, StandardCharsets.UTF_8);
                if (latest.length() >= expectedMinimum) return latest;
            }
            Thread.sleep(25);
        }
        return latest;
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
