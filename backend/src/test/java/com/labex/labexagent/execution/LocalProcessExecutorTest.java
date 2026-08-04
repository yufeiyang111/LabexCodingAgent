package com.labex.labexagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.runtime.CancellationToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProcessExecutorTest {
    @TempDir
    Path tempDir;

    private final LocalProcessExecutor executor = new LocalProcessExecutor();

    @Test
    void drainsLargeOutputWhileProcessIsRunning() {
        AtomicInteger streamedCharacters = new AtomicInteger();
        ProcessExecutionRequest request = request(Duration.ofSeconds(10), 4096, "large", "250000");

        ProcessExecutionResult result = executor.execute(
                request,
                CancellationToken.none(),
                chunk -> streamedCharacters.addAndGet(chunk.length()));

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).hasSize(4096);
        assertThat(result.truncated()).isTrue();
        assertThat(streamedCharacters.get()).isGreaterThanOrEqualTo(250000);
    }

    @Test
    void reportsNonZeroExitAsFailed() {
        ProcessExecutionResult result = executor.execute(
                request(Duration.ofSeconds(5), 4096, "exit", "7"));

        assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.exitCode()).isEqualTo(7);
    }

    @Test
    void terminatesProcessAfterTimeout() {
        ProcessExecutionResult result = executor.execute(
                request(Duration.ofMillis(200), 4096, "sleep", "10000"));

        assertThat(result.status()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(result.exitCode()).isNull();
        assertThat(result.durationMs()).isLessThan(5000);
    }

    @Test
    void terminatesProcessWhenCancellationIsRequested() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> cancelled.set(true), 150, TimeUnit.MILLISECONDS);

        try {
            ProcessExecutionResult result = executor.execute(
                    request(Duration.ofSeconds(10), 4096, "sleep", "10000"),
                    cancelled::get,
                    chunk -> { });

            assertThat(result.status()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(result.exitCode()).isNull();
            assertThat(result.durationMs()).isLessThan(5000);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void terminatesDescendantProcessesWhenCancelled() throws Exception {
        Path childPidFile = tempDir.resolve("child.pid");
        AtomicBoolean cancelled = new AtomicBoolean();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.execute(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(childPidFile) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            cancelled.set(true);
        });

        try {
            ProcessExecutionResult result = executor.execute(
                    request(Duration.ofSeconds(10), 4096, "spawn-child", childPidFile.toString()),
                    cancelled::get,
                    chunk -> { });

            assertThat(result.status()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(childPidFile).exists();
            long childPid = Long.parseLong(Files.readString(childPidFile).trim());
            assertThat(waitUntilTerminated(childPid, Duration.ofSeconds(2))).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void reportsVerifiableIdentityOnlyAfterTheProcessStarts() {
        AtomicReference<ProcessExecutionIdentity> observed = new AtomicReference<>();

        ProcessExecutionResult result = executor.execute(
                request(Duration.ofSeconds(5), 4096, "exit", "0"),
                CancellationToken.none(),
                chunk -> { },
                observed::set);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().ownerId()).isNotBlank();
        assertThat(observed.get().processId()).isPositive();
        assertThat(observed.get().processStartEpochMs()).isPositive();
        assertThat(observed.get().leaseExpiresEpochMs())
                .isGreaterThan(observed.get().processStartEpochMs());
    }

    @Test
    void terminatesTheRealProcessWhenIdentityPersistenceFails() throws Exception {
        AtomicReference<ProcessExecutionIdentity> observed = new AtomicReference<>();

        ProcessExecutionRequest fixtureRequest = request(Duration.ofSeconds(30), 4096, "sleep", "30000");
        ProcessExecutionRequest longRunningRequest = new ProcessExecutionRequest(
                fixtureRequest.command(), Path.of(System.getProperty("user.dir")),
                fixtureRequest.timeout(), fixtureRequest.maxOutputChars());
        ProcessExecutionResult result = executor.execute(
                longRunningRequest,
                CancellationToken.none(),
                chunk -> { },
                identity -> {
                    observed.set(identity);
                    throw new IllegalStateException("durable process identity unavailable");
                });

        assertThat(result.status()).isEqualTo(ExecutionStatus.INFRASTRUCTURE_ERROR);
        assertThat(observed.get()).isNotNull();
        assertThat(waitUntilTerminated(observed.get().processId(), Duration.ofSeconds(2))).isTrue();
    }

    private ProcessExecutionRequest request(Duration timeout, int maxOutputChars, String... fixtureArgs) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ProcessFixture.class.getName());
        command.addAll(List.of(fixtureArgs));
        return new ProcessExecutionRequest(command, tempDir, timeout, maxOutputChars);
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private boolean waitUntilTerminated(long pid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(25);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    public static final class ProcessFixture {
        private ProcessFixture() {
        }

        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "large" -> {
                    System.out.print("x".repeat(Integer.parseInt(args[1])));
                    System.out.flush();
                }
                case "exit" -> System.exit(Integer.parseInt(args[1]));
                case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
                case "spawn-child" -> {
                    String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
                    Process child = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            ProcessFixture.class.getName(),
                            "sleep",
                            "10000")
                            .start();
                    Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
                    Thread.sleep(10000);
                }
                default -> throw new IllegalArgumentException("Unknown fixture mode: " + args[0]);
            }
        }
    }
}
