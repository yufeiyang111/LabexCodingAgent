package com.labex.labexagent.execution;

import com.labex.labexagent.runtime.CancellationToken;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class LocalProcessExecutor implements ProcessExecutor {
    private static final long POLL_MILLIS = 50L;
    private static final long TERMINATION_GRACE_MILLIS = 500L;
    private static final long OUTPUT_JOIN_MILLIS = 2000L;
    private final ProcessHostIdentity hostIdentity;
    private final String ownerId;

    public LocalProcessExecutor() {
        this(ProcessHostIdentity.localDefault(), "local-executor-" + UUID.randomUUID());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LocalProcessExecutor(ProcessHostIdentity hostIdentity) {
        this(hostIdentity, "local-executor-" + UUID.randomUUID());
    }

    LocalProcessExecutor(String ownerId) {
        this(ProcessHostIdentity.localDefault(), ownerId);
    }

    LocalProcessExecutor(ProcessHostIdentity hostIdentity, String ownerId) {
        this.hostIdentity = Objects.requireNonNull(hostIdentity, "hostIdentity");
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
        this.ownerId = ownerId;
    }

    @Override
    public ProcessExecutionResult execute(
            ProcessExecutionRequest request,
            CancellationToken cancellationToken,
            Consumer<String> outputListener) {
        return execute(request, cancellationToken, outputListener, ProcessExecutionObserver.none());
    }

    @Override
    public ProcessExecutionResult execute(
            ProcessExecutionRequest request,
            CancellationToken cancellationToken,
            Consumer<String> outputListener,
            ProcessExecutionObserver observer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        Objects.requireNonNull(outputListener, "outputListener");
        Objects.requireNonNull(observer, "observer");

        long startedAt = System.nanoTime();
        if (!request.workingDirectory().toFile().isDirectory()) {
            return infrastructureError(startedAt, "Working directory does not exist: " + request.workingDirectory());
        }

        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(ProcessCommandResolver.resolve(request.command()))
                    .directory(request.workingDirectory().toFile())
                    .redirectErrorStream(true);
            if (!request.environment().isEmpty()) {
                processBuilder.environment().clear();
                processBuilder.environment().putAll(request.environment());
            }
            process = processBuilder.start();
        } catch (IOException e) {
            return infrastructureError(startedAt, e.getMessage());
        }

        ProcessExecutionIdentity identity = identity(process, request.timeout());
        try {
            observer.onStarted(identity);
        } catch (RuntimeException persistenceFailure) {
            terminateProcessTree(process);
            closeProcessStreams(process);
            return infrastructureError(startedAt,
                    "Process identity persistence failed: " + persistenceFailure.getMessage());
        }

        BoundedOutput output;
        try {
            output = new BoundedOutput(request.maxOutputChars(), request.outputArtifactPath());
        } catch (RuntimeException artifactFailure) {
            terminateProcessTree(process);
            closeProcessStreams(process);
            return infrastructureError(startedAt, artifactFailure.getMessage());
        }
        AtomicReference<IOException> outputFailure = new AtomicReference<>();
        Thread outputThread = new Thread(
                () -> drainOutput(process, output, outputListener, outputFailure),
                "labex-process-output-" + process.pid());
        outputThread.setDaemon(true);
        outputThread.start();

        ExecutionStatus status;
        Integer exitCode = null;
        long deadline = startedAt + request.timeout().toNanos();
        try {
            while (true) {
                if (cancellationToken.isCancellationRequested()) {
                    status = ExecutionStatus.CANCELLED;
                    terminateProcessTree(process);
                    break;
                }

                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    status = ExecutionStatus.TIMED_OUT;
                    terminateProcessTree(process);
                    break;
                }

                long waitMillis = Math.max(1L, Math.min(
                        POLL_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    status = exitCode == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = ExecutionStatus.CANCELLED;
            terminateProcessTree(process);
        }

        joinOutput(outputThread, process);
        try {
            output.close();
        } catch (IOException closeFailure) {
            outputFailure.compareAndSet(null, closeFailure);
        }
        String visibleOutput = output.visibleContent();
        if ((status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.FAILED)
                && outputFailure.get() != null) {
            return new ProcessExecutionResult(
                    ExecutionStatus.INFRASTRUCTURE_ERROR,
                    exitCode,
                    elapsedMillis(startedAt),
                    appendError(visibleOutput, outputFailure.get().getMessage()),
                    output.truncated(),
                    output.artifactPath(),
                    output.totalChars());
        }
        return new ProcessExecutionResult(
                status,
                exitCode,
                elapsedMillis(startedAt),
                visibleOutput,
                output.truncated(),
                output.artifactPath(),
                output.totalChars());
    }

    private ProcessExecutionIdentity identity(Process process, Duration timeout) {
        Long processStartEpochMs = process.toHandle().info().startInstant()
                .map(Instant::toEpochMilli)
                .orElse(null);
        long leaseExpiresEpochMs = System.currentTimeMillis()
                + timeout.toMillis()
                + TERMINATION_GRACE_MILLIS;
        return new ProcessExecutionIdentity(
                hostIdentity.hostId(), ownerId, "host", "", process.pid(),
                processStartEpochMs, leaseExpiresEpochMs);
    }

    private void closeProcessStreams(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    private void drainOutput(
            Process process,
            BoundedOutput output,
            Consumer<String> outputListener,
            AtomicReference<IOException> outputFailure) {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read);
                output.append(chunk);
                try {
                    outputListener.accept(chunk);
                } catch (RuntimeException ignored) {
                    // Artifact streaming must not block process output draining.
                }
            }
        } catch (IOException e) {
            if (process.isAlive()) {
                outputFailure.compareAndSet(null, e);
            }
        }
    }

    private void joinOutput(Thread outputThread, Process process) {
        try {
            outputThread.join(OUTPUT_JOIN_MILLIS);
            if (outputThread.isAlive()) {
                process.getInputStream().close();
                outputThread.interrupt();
                outputThread.join(TERMINATION_GRACE_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outputThread.interrupt();
        } catch (IOException ignored) {
            outputThread.interrupt();
        }
    }

    private void terminateProcessTree(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();

        try {
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        waitForHandles(descendants, TERMINATION_GRACE_MILLIS);
    }

    private void waitForHandles(List<ProcessHandle> handles, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private ProcessExecutionResult infrastructureError(long startedAt, String message) {
        return new ProcessExecutionResult(
                ExecutionStatus.INFRASTRUCTURE_ERROR,
                null,
                elapsedMillis(startedAt),
                message == null ? "Process execution failed" : message,
                false);
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String appendError(String output, String error) {
        if (error == null || error.isBlank()) {
            return output;
        }
        return output + (output.isBlank() ? "" : "\n") + "Output read failed: " + error;
    }

    private static final class BoundedOutput implements AutoCloseable {
        private static final int TAIL_LIMIT = 4_096;
        private final int limit;
        private final StringBuilder head;
        private final StringBuilder tail;
        private final Path artifactPath;
        private final java.io.Writer artifactWriter;
        private long totalChars;
        private boolean truncated;

        private BoundedOutput(int limit, Path artifactPath) {
            this.limit = limit;
            this.head = new StringBuilder(Math.min(limit, 8192));
            this.tail = new StringBuilder(Math.min(TAIL_LIMIT, 8192));
            this.artifactPath = artifactPath == null ? null : artifactPath.toAbsolutePath().normalize();
            try {
                if (this.artifactPath != null) {
                    Path parent = this.artifactPath.getParent();
                    if (parent != null) {
                        java.nio.file.Files.createDirectories(parent);
                    }
                    this.artifactWriter = java.nio.file.Files.newBufferedWriter(this.artifactPath,
                            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.StandardOpenOption.WRITE);
                } else {
                    this.artifactWriter = null;
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to create process output artifact", failure);
            }
        }

        private synchronized void append(String chunk) throws IOException {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            totalChars += chunk.length();
            if (artifactWriter != null) {
                artifactWriter.write(chunk);
            }
            int remaining = limit - head.length();
            if (remaining <= 0) {
                truncated = true;
            } else if (chunk.length() <= remaining) {
                head.append(chunk);
            } else {
                head.append(chunk, 0, remaining);
                truncated = true;
            }
            if (truncated) {
                appendTail(chunk);
            }
        }

        private void appendTail(String chunk) {
            tail.append(chunk);
            if (tail.length() > TAIL_LIMIT) {
                tail.delete(0, tail.length() - TAIL_LIMIT);
            }
        }

        private synchronized String visibleContent() {
            if (!truncated || artifactPath == null) {
                return head.toString();
            }
            return head + "\n[output truncated; total_chars=" + totalChars
                    + "; full_output_path=" + artifactPath() + "; tail follows]\n" + tail;
        }

        private boolean truncated() {
            return truncated;
        }

        private long totalChars() {
            return totalChars;
        }

        private String artifactPath() {
            return artifactPath == null ? null : artifactPath.toString();
        }

        @Override
        public synchronized void close() throws IOException {
            if (artifactWriter != null) {
                artifactWriter.close();
            }
        }
    }
}
