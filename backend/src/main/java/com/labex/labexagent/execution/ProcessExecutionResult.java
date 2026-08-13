package com.labex.labexagent.execution;

public record ProcessExecutionResult(
        ExecutionStatus status,
        Integer exitCode,
        long durationMs,
        String output,
        boolean truncated,
        String outputPath,
        long outputChars) {

    public ProcessExecutionResult(
            ExecutionStatus status,
            Integer exitCode,
            long durationMs,
            String output,
            boolean truncated) {
        this(status, exitCode, durationMs, output, truncated, null, output == null ? 0L : output.length());
    }

    public ProcessExecutionResult {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs cannot be negative");
        }
        if (outputChars < 0) {
            throw new IllegalArgumentException("outputChars cannot be negative");
        }
        output = output == null ? "" : output;
        outputPath = outputPath == null || outputPath.isBlank() ? null : outputPath;
    }

    public boolean succeeded() {
        return status == ExecutionStatus.SUCCEEDED && Integer.valueOf(0).equals(exitCode);
    }
}
