package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.llm.LlmProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CancellationPropagationIntegrationTest {

    @TempDir
    Path workspace;

    @Test
    void oneRunCancellationStopsTheBlockingProviderAndLongRunningProcess() throws Exception {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun run = registry.register("session-9", 7, 12, 53L);
        BlockingProvider provider = new BlockingProvider();
        Path processStarted = workspace.resolve("process-started");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> providerFuture = executor.submit(() -> provider.chatStream(
                    "system", List.of(), List.of(),
                    new LlmProvider.LlmConfig("key", "https://example.test", "model", 32, 0.1),
                    run,
                    ignored -> { }));
            Future<ProcessExecutionResult> processFuture = executor.submit(() -> new LocalProcessExecutor().execute(
                    processRequest(processStarted), run, ignored -> { }));

            assertTrue(provider.started.await(1, TimeUnit.SECONDS));
            waitForFile(processStarted, Duration.ofSeconds(2));
            registry.cancel("session-9", 7, 12);

            providerFuture.get(1, TimeUnit.SECONDS);
            ProcessExecutionResult processResult = processFuture.get(3, TimeUnit.SECONDS);
            assertTrue(provider.cancelled.get());
            assertEquals(ExecutionStatus.CANCELLED, processResult.status());
        } finally {
            executor.shutdownNow();
        }
    }

    private ProcessExecutionRequest processRequest(Path processStarted) {
        return new ProcessExecutionRequest(
                List.of(
                        javaExecutable(),
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        LongRunningProcess.class.getName(),
                        processStarted.toString()),
                workspace,
                Duration.ofSeconds(10),
                4096);
    }

    private void waitForFile(Path path, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(path));
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static final class BlockingProvider implements LlmProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public String getProviderId() {
            return "blocking";
        }

        @Override
        public String getProviderName() {
            return "blocking";
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public boolean supportsToolCalling() {
            return false;
        }

        @Override
        public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                                 List<Map<String, Object>> tools, LlmConfig config) {
            return Map.of();
        }

        @Override
        public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                               List<Map<String, Object>> tools, LlmConfig config,
                               java.util.function.Consumer<StreamChunk> onChunk) {
            throw new UnsupportedOperationException("The cancellation-aware overload must be used");
        }

        @Override
        public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                               List<Map<String, Object>> tools, LlmConfig config,
                               CancellationToken cancellationToken,
                               java.util.function.Consumer<StreamChunk> onChunk) {
            started.countDown();
            try (CancellationToken.Registration ignored = cancellationToken.onCancellation(() -> cancelled.set(true))) {
                while (!cancellationToken.isCancellationRequested()) {
                    Thread.sleep(10);
                }
                onChunk.accept(new StreamChunk("cancelled", "cancelled", null, null, null, true, null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static final class LongRunningProcess {
        private LongRunningProcess() {
        }

        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "started");
            Thread.sleep(10_000L);
        }
    }
}
