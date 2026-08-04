package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.CommandAuditEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandProcessRecoveryServiceTest {

    @TempDir
    Path tempDir;

    private final com.labex.labexagent.execution.ProcessHostIdentity hostIdentity =
            new com.labex.labexagent.execution.ProcessHostIdentity("host-71");
    private final CommandProcessRecoveryService service = new CommandProcessRecoveryService(hostIdentity);

    @Test
    void terminatesOnlyTheProcessWhosePidAndStartTimeBothMatch() throws Exception {
        Process process = startSleeper();
        try {
            CommandAuditEvent binding = binding(process, 0L);

            CommandProcessRecoveryService.RecoveryResult result = service.recover(binding);

            assertThat(result).isEqualTo(CommandProcessRecoveryService.RecoveryResult.TERMINATED);
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void terminatesTheCapturedDescendantProcessesBeforeReportingSuccess() throws Exception {
        Path childPidFile = tempDir.resolve("recovery-child.pid");
        Process process = startSleeperWithChild(childPidFile);
        long childPid = waitForChildPid(childPidFile);
        try {
            CommandAuditEvent binding = binding(process, 0L);

            CommandProcessRecoveryService.RecoveryResult result = service.recover(binding);

            assertThat(result).isEqualTo(CommandProcessRecoveryService.RecoveryResult.TERMINATED);
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilTerminated(childPid, Duration.ofSeconds(2))).isTrue();
        } finally {
            process.destroyForcibly();
            ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void refusesToKillAReusedPidWhenTheStartTimeDoesNotMatch() throws Exception {
        Process process = startSleeper();
        try {
            CommandAuditEvent binding = binding(process, 1L);

            CommandProcessRecoveryService.RecoveryResult result = service.recover(binding);

            assertThat(result).isEqualTo(CommandProcessRecoveryService.RecoveryResult.IDENTITY_MISMATCH);
            assertThat(process.isAlive()).isTrue();
        } finally {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void refusesToInspectOrKillAPidPersistedForAnotherHost() throws Exception {
        Process process = startSleeper();
        try {
            CommandAuditEvent binding = binding(process, 0L);
            binding.setProcessHostId(new com.labex.labexagent.execution.ProcessHostIdentity("other-host").hostId());

            CommandProcessRecoveryService.RecoveryResult result = service.recover(binding);

            assertThat(result).isEqualTo(CommandProcessRecoveryService.RecoveryResult.DIFFERENT_HOST);
            assertThat(process.isAlive()).isTrue();
        } finally {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private CommandAuditEvent binding(Process process, long startOffsetMs) {
        long startedAt = process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        CommandAuditEvent event = new CommandAuditEvent();
        event.setEventType("EXECUTION_PROCESS_BOUND");
        event.setExecutionStatus("running");
        event.setProcessHostId(hostIdentity.hostId());
        event.setProcessOwner("previous-jvm");
        event.setWorkerRuntime("local");
        event.setWorkerRunId("task-71");
        event.setProcessId(process.pid());
        event.setProcessStartEpochMs(startedAt + startOffsetMs);
        event.setProcessLeaseExpiresEpochMs(System.currentTimeMillis() + Duration.ofSeconds(30).toMillis());
        return event;
    }

    private Process startSleeper() throws Exception {
        Path source = writeSleeperSource();
        return new ProcessBuilder(javaExecutable(), source.toString(), "sleep").start();
    }

    private Process startSleeperWithChild(Path childPidFile) throws Exception {
        Path source = writeSleeperSource();
        return new ProcessBuilder(
                javaExecutable(), source.toString(), "spawn-child", source.toString(), childPidFile.toString())
                .start();
    }

    private Path writeSleeperSource() throws Exception {
        Path source = tempDir.resolve("RecoverySleeper.java");
        Files.writeString(source, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class RecoverySleeper {
                    public static void main(String[] args) throws Exception {
                        if (args.length > 0 && "spawn-child".equals(args[0])) {
                            Process child = new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin",
                                            System.getProperty("os.name").toLowerCase().contains("win")
                                                    ? "java.exe" : "java").toString(),
                                    args[1], "sleep")
                                    .start();
                            Files.writeString(Path.of(args[2]), Long.toString(child.pid()));
                        }
                        Thread.sleep(30000L);
                    }
                }
                """);
        return source;
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private long waitForChildPid(Path childPidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(childPidFile)) {
                return Long.parseLong(Files.readString(childPidFile).trim());
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("child process pid was not written");
    }

    private boolean waitUntilTerminated(long pid, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(25L);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }
}
