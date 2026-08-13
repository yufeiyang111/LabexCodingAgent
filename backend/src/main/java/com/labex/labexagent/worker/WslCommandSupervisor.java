package com.labex.labexagent.worker;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.CancellationToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WSL 非交互命令的一次性优雅终止协议。
 * 控制目录只存在于 workspace 的 worker-tmp 中，不参与 transcript、审批或运行状态持久化。
 */
final class WslCommandSupervisor {
    private static final long GRACEFUL_TERMINATION_GRACE_MILLIS = 2_500L;
    private static final String SUPERVISOR_SCRIPT = """
            #!/bin/sh
            set -u
            control_dir=$1
            shift
            if [ "${1:-}" = "--" ]; then
              shift
            fi
            child_pid=
            monitor_pid=
            stop_child() {
              kind=$1
              printf '%s\n' "$kind" > "$control_dir/state"
              if [ -n "$child_pid" ] && kill -0 "$child_pid" 2>/dev/null; then
                kill -TERM -- "-$child_pid" 2>/dev/null || kill -TERM "$child_pid" 2>/dev/null || true
                sleep 0.25
                kill -KILL -- "-$child_pid" 2>/dev/null || kill -KILL "$child_pid" 2>/dev/null || true
              fi
            }
            monitor_child() {
              while kill -0 "$child_pid" 2>/dev/null; do
                if [ -f "$control_dir/cancel" ]; then
                  stop_child cancelled
                  return
                fi
                if [ -f "$control_dir/timeout" ]; then
                  stop_child timed_out
                  return
                fi
                sleep 0.05
              done
            }
            # bwrap --new-session makes this wrapper a process-group leader. Without --wait,
            # util-linux setsid may fork and return before the real command has completed.
            setsid --wait "$@" &
            child_pid=$!
            monitor_child &
            monitor_pid=$!
            wait "$child_pid"
            child_code=$?
            if [ -n "$monitor_pid" ]; then
              kill "$monitor_pid" 2>/dev/null || true
            fi
            if [ -f "$control_dir/cancel" ]; then
              printf '%s\n' cancelled > "$control_dir/state"
              exit 125
            fi
            if [ -f "$control_dir/timeout" ]; then
              printf '%s\n' timed_out > "$control_dir/state"
              exit 124
            fi
            printf '%s\n' completed > "$control_dir/state"
            exit "$child_code"
            """;

    private WslCommandSupervisor() { }

    static Execution open(
            WorkerRunSpec run, ProcessExecutionRequest request, CancellationToken sourceToken) throws IOException {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sourceToken, "sourceToken");
        Path workspace = run.workspaceRoot().toAbsolutePath().normalize();
        Path workerTempRoot = workspace.resolve(".labex-agent").resolve("worker-tmp").normalize();
        if (!workerTempRoot.startsWith(workspace)) {
            throw new IllegalArgumentException("WSL supervisor control path escapes workspace");
        }
        Files.createDirectories(workerTempRoot);
        Path hostDirectory = Files.createTempDirectory(workerTempRoot, "wsl-exec-");
        Path supervisor = hostDirectory.resolve("supervisor.sh");
        Files.writeString(supervisor, SUPERVISOR_SCRIPT, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Path commandScript = hostDirectory.resolve("command.sh");
        List<String> stagedCommand = stageShellPayload(
                commandScript, sandboxPath(workspace, commandScript), request.command());
        return new Execution(
                hostDirectory,
                sandboxPath(workspace, hostDirectory),
                sandboxPath(workspace, supervisor),
                stagedCommand,
                request.timeout(),
                sourceToken);
    }

    /**
     * Windows ProcessBuilder -> wsl.exe 传递 Shell payload 时，先写入 workspace 脚本，
     * 避免 Windows 到 WSL 的引号转义改变命令语义。
     */
    private static List<String> stageShellPayload(
            Path hostScript, String sandboxScript, List<String> originalCommand) throws IOException {
        int commandFlagIndex = shellCommandFlagIndex(originalCommand);
        if (commandFlagIndex < 0 || commandFlagIndex + 1 != originalCommand.size() - 1) {
            return List.copyOf(originalCommand);
        }
        String payload = originalCommand.get(commandFlagIndex + 1);
        if (payload == null || payload.isBlank()) {
            return List.copyOf(originalCommand);
        }
        Files.writeString(hostScript, payload, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        List<String> staged = new ArrayList<>();
        for (int index = 0; index < originalCommand.size(); index++) {
            if (index == commandFlagIndex) {
                String retainedFlag = removeCommandFlag(originalCommand.get(index));
                if (!retainedFlag.isBlank()) {
                    staged.add(retainedFlag);
                }
                continue;
            }
            if (index != commandFlagIndex + 1) {
                staged.add(originalCommand.get(index));
            }
        }
        staged.add(sandboxScript);
        return List.copyOf(staged);
    }

    private static int shellCommandFlagIndex(List<String> command) {
        if (command == null || command.size() < 3 || !isPosixShell(command.get(0))) {
            return -1;
        }
        for (int index = 1; index + 1 < command.size(); index++) {
            String argument = command.get(index);
            if ("--command".equals(argument)) {
                return index;
            }
            if (argument != null && argument.matches("-[A-Za-z]+") && argument.indexOf('c') >= 1) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isPosixShell(String executable) {
        return "/bin/bash".equals(executable) || "/bin/sh".equals(executable)
                || "/usr/bin/bash".equals(executable) || "/usr/bin/sh".equals(executable)
                || "bash".equals(executable) || "sh".equals(executable)
                || "zsh".equals(executable) || "dash".equals(executable);
    }

    private static String removeCommandFlag(String argument) {
        if ("--command".equals(argument)) {
            return "";
        }
        if (argument == null || !argument.startsWith("-")) {
            return argument == null ? "" : argument;
        }
        String retained = argument.substring(0, argument.indexOf('c'))
                + argument.substring(argument.indexOf('c') + 1);
        return "-".equals(retained) ? "" : retained;
    }

    private static String sandboxPath(Path workspace, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) {
            throw new IllegalArgumentException("WSL supervisor path escapes workspace");
        }
        String relative = workspace.relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "/workspace" : "/workspace/" + relative;
    }

    static final class Execution implements AutoCloseable {
        private final Path hostDirectory;
        private final Path cancelMarker;
        private final Path timeoutMarker;
        private final List<String> command;
        private final Duration processTimeout;
        private final DelayedCancellation cancellation;

        private Execution(
                Path hostDirectory,
                String sandboxDirectory,
                String supervisorPath,
                List<String> stagedCommand,
                Duration timeout,
                CancellationToken sourceToken) {
            this.hostDirectory = hostDirectory;
            this.cancelMarker = hostDirectory.resolve("cancel");
            this.timeoutMarker = hostDirectory.resolve("timeout");
            List<String> wrapped = new ArrayList<>();
            wrapped.add("/bin/sh");
            wrapped.add(supervisorPath);
            wrapped.add(sandboxDirectory);
            wrapped.add("--");
            wrapped.addAll(stagedCommand);
            this.command = List.copyOf(wrapped);
            this.processTimeout = timeout.plusMillis(GRACEFUL_TERMINATION_GRACE_MILLIS);
            this.cancellation = new DelayedCancellation(
                    sourceToken,
                    System.nanoTime() + timeout.toNanos(),
                    cancelMarker,
                    timeoutMarker);
        }

        List<String> command() {
            return command;
        }

        Duration processTimeout() {
            return processTimeout;
        }

        CancellationToken cancellationToken() {
            return cancellation;
        }

        ProcessExecutionResult translate(ProcessExecutionResult result) {
            if (cancellation.timeoutRequested() && !result.succeeded()) {
                return withStatus(result, ExecutionStatus.TIMED_OUT);
            }
            if (cancellation.cancellationRequested() && !result.succeeded()) {
                return withStatus(result, ExecutionStatus.CANCELLED);
            }
            return result;
        }

        @Override
        public void close() {
            cancellation.close();
            cleanup(hostDirectory);
        }

        private ProcessExecutionResult withStatus(ProcessExecutionResult result, ExecutionStatus status) {
            return new ProcessExecutionResult(
                    status, result.exitCode(), result.durationMs(), result.output(), result.truncated(),
                    result.outputPath(), result.outputChars());
        }
    }

    private static final class DelayedCancellation implements CancellationToken {
        private final CancellationToken source;
        private final long deadlineNanos;
        private final Path cancelMarker;
        private final Path timeoutMarker;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean timeoutRequested = new AtomicBoolean();
        private final AtomicLong gracefulStopAtNanos = new AtomicLong();
        private final Registration sourceRegistration;

        private DelayedCancellation(
                CancellationToken source, long deadlineNanos, Path cancelMarker, Path timeoutMarker) {
            this.source = source;
            this.deadlineNanos = deadlineNanos;
            this.cancelMarker = cancelMarker;
            this.timeoutMarker = timeoutMarker;
            this.sourceRegistration = source.onCancellation(this::requestCancellation);
        }

        @Override
        public boolean isCancellationRequested() {
            if (source.isCancellationRequested()) {
                requestCancellation();
            } else if (!cancellationRequested.get() && !timeoutRequested.get()
                    && System.nanoTime() >= deadlineNanos) {
                requestTimeout();
            }
            long requestedAt = gracefulStopAtNanos.get();
            return requestedAt > 0L
                    && System.nanoTime() - requestedAt
                    >= TimeUnit.MILLISECONDS.toNanos(GRACEFUL_TERMINATION_GRACE_MILLIS);
        }

        @Override
        public Registration onCancellation(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            return source.onCancellation(() -> {
                requestCancellation();
                listener.run();
            });
        }

        private void requestCancellation() {
            if (cancellationRequested.compareAndSet(false, true)) {
                writeMarker(cancelMarker);
                gracefulStopAtNanos.compareAndSet(0L, System.nanoTime());
            }
        }

        private void requestTimeout() {
            if (timeoutRequested.compareAndSet(false, true)) {
                writeMarker(timeoutMarker);
                gracefulStopAtNanos.compareAndSet(0L, System.nanoTime());
            }
        }

        private boolean cancellationRequested() {
            return cancellationRequested.get();
        }

        private boolean timeoutRequested() {
            return timeoutRequested.get();
        }

        private void close() {
            sourceRegistration.close();
        }
    }

    private static void writeMarker(Path marker) {
        try {
            Files.writeString(marker, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // 控制文件失败时保留 LocalProcessExecutor 的强制终止兜底。
        }
    }

    private static void cleanup(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不能覆盖原始命令结果。
                }
            });
        } catch (IOException ignored) {
            // 保留残留控制目录比扩大删除范围更安全。
        }
    }
}
