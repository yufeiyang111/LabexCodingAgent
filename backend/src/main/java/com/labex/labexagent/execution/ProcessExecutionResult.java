package com.labex.labexagent.execution;

public record ProcessExecutionResult(
        ExecutionStatus status,
        Integer exitCode,
        long durationMs,
        String output,
        boolean truncated) {

    public ProcessExecutionResult {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs cannot be negative");
        }
        output = output == null ? "" : output;
    }

    public boolean succeeded() {
        return status == ExecutionStatus.SUCCEEDED && Integer.valueOf(0).equals(exitCode);
    }
}
