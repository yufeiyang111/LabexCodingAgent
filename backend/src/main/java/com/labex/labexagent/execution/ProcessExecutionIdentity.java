package com.labex.labexagent.execution;

/** 一次已启动进程执行的非敏感操作系统身份。 */
public record ProcessExecutionIdentity(
        String hostId,
        String ownerId,
        String workerRuntime,
        String workerRunId,
        long processId,
        Long processStartEpochMs,
        long leaseExpiresEpochMs) {

    public ProcessExecutionIdentity {
        if (hostId == null || hostId.isBlank()) {
            throw new IllegalArgumentException("hostId is required");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
        workerRuntime = workerRuntime == null || workerRuntime.isBlank() ? "host" : workerRuntime.trim();
        workerRunId = workerRunId == null ? "" : workerRunId.trim();
        if (processId <= 0L) {
            throw new IllegalArgumentException("processId must be positive");
        }
        if (processStartEpochMs != null && processStartEpochMs <= 0L) {
            throw new IllegalArgumentException("processStartEpochMs must be positive when present");
        }
        if (leaseExpiresEpochMs <= 0L
                || (processStartEpochMs != null && leaseExpiresEpochMs <= processStartEpochMs)) {
            throw new IllegalArgumentException("leaseExpiresEpochMs must be after process start");
        }
    }

    public ProcessExecutionIdentity withWorkerContext(String runtime, String runId) {
        if (runtime == null || runtime.isBlank() || runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("worker runtime and runId are required");
        }
        return new ProcessExecutionIdentity(
                hostId, ownerId, runtime, runId, processId, processStartEpochMs, leaseExpiresEpochMs);
    }

    public boolean verifiable() {
        return processStartEpochMs != null;
    }
}
