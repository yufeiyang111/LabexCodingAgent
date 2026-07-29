package com.labex.labexagent.execution;

import com.labex.labexagent.runtime.CancellationToken;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class LocalProcessExecutor implements ProcessExecutor {
    private static final long POLL_MILLIS = 50L;
    private static final long TERMINATION_GRACE_MILLIS = 500L;
    private static final long OUTPUT_JOIN_MILLIS = 2000L;

    @Override
    public ProcessExecutionResult execute(
            ProcessExecutionRequest request,
            CancellationToken cancellationToken,
            Consumer<String> outputListener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        Objects.requireNonNull(outputListener, "outputListener");

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

        BoundedOutput output = new BoundedOutput(request.maxOutputChars());
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
        if ((status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.FAILED)
                && outputFailure.get() != null) {
            return new ProcessExecutionResult(
                    ExecutionStatus.INFRASTRUCTURE_ERROR,
                    exitCode,
                    elapsedMillis(startedAt),
                    appendError(output.content(), outputFailure.get().getMessage()),
                    output.truncated());
        }
        return new ProcessExecutionResult(
                status,
                exitCode,
                elapsedMillis(startedAt),
                output.content(),
                output.truncated());
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

    private static final class BoundedOutput {
        private final int limit;
        private final StringBuilder content;
        private boolean truncated;

        private BoundedOutput(int limit) {
            this.limit = limit;
            this.content = new StringBuilder(Math.min(limit, 8192));
        }

        private void append(String chunk) {
            int remaining = limit - content.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            if (chunk.length() <= remaining) {
                content.append(chunk);
                return;
            }
            content.append(chunk, 0, remaining);
            truncated = true;
        }

        private String content() {
            return content.toString();
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
