package com.labex.labexagent.commandsecurity;

import com.labex.entity.CommandAuditEvent;
import com.labex.labexagent.execution.ProcessHostIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** 只有持久 PID 和启动时间仍指向同一进程时，才安全回收宿主进程。 */
@Service
public class CommandProcessRecoveryService {
    private static final long TERMINATION_GRACE_MILLIS = 500L;
    private final ProcessHostIdentity hostIdentity;

    public CommandProcessRecoveryService(ProcessHostIdentity hostIdentity) {
        this.hostIdentity = hostIdentity;
    }

    public RecoveryResult recover(CommandAuditEvent binding) {
        if (!hasCompleteIdentity(binding)) {
            return RecoveryResult.UNVERIFIABLE;
        }
        if (!hostIdentity.hostId().equals(binding.getProcessHostId())) {
            return RecoveryResult.DIFFERENT_HOST;
        }
        if (!supportsHostProcessRecovery(binding.getWorkerRuntime())) {
            return RecoveryResult.UNSUPPORTED_RUNTIME;
        }
        Optional<ProcessHandle> candidate = ProcessHandle.of(binding.getProcessId());
        if (candidate.isEmpty() || !candidate.get().isAlive()) {
            return RecoveryResult.NOT_RUNNING;
        }
        ProcessHandle process = candidate.get();
        Optional<Instant> actualStart = process.info().startInstant();
        if (actualStart.isEmpty()) {
            return RecoveryResult.UNVERIFIABLE;
        }
        if (actualStart.get().toEpochMilli() != binding.getProcessStartEpochMs()) {
            return RecoveryResult.IDENTITY_MISMATCH;
        }
        return terminateProcessTree(process)
                ? RecoveryResult.TERMINATED
                : RecoveryResult.TERMINATION_FAILED;
    }

    private boolean hasCompleteIdentity(CommandAuditEvent binding) {
        return binding != null
                && binding.getProcessHostId() != null
                && !binding.getProcessHostId().isBlank()
                && binding.getProcessId() != null
                && binding.getProcessId() > 0L
                && binding.getProcessStartEpochMs() != null
                && binding.getProcessStartEpochMs() > 0L;
    }

    private boolean supportsHostProcessRecovery(String workerRuntime) {
        return "local".equals(workerRuntime) || "wsl".equals(workerRuntime) || "host".equals(workerRuntime);
    }

    private boolean terminateProcessTree(ProcessHandle process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        descendants.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        List<ProcessHandle> processTree = new ArrayList<>(descendants);
        processTree.add(process);

        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        waitForExit(processTree, TERMINATION_GRACE_MILLIS);
        processTree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        waitForExit(processTree, TERMINATION_GRACE_MILLIS);
        return processTree.stream().noneMatch(ProcessHandle::isAlive);
    }

    private void waitForExit(List<ProcessHandle> processes, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public enum RecoveryResult {
        TERMINATED,
        NOT_RUNNING,
        ALREADY_INTERRUPTED,
        IDENTITY_MISMATCH,
        UNVERIFIABLE,
        UNSUPPORTED_RUNTIME,
        DIFFERENT_HOST,
        TERMINATION_FAILED
    }
}
