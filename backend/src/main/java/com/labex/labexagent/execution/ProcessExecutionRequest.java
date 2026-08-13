package com.labex.labexagent.execution;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProcessExecutionRequest(
        List<String> command,
        Path workingDirectory,
        Duration timeout,
        int maxOutputChars,
        Map<String, String> environment,
        Path outputArtifactPath) {

    public ProcessExecutionRequest(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputChars) {
        this(command, workingDirectory, timeout, maxOutputChars, Map.of(), null);
    }

    public ProcessExecutionRequest(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputChars,
            Map<String, String> environment) {
        this(command, workingDirectory, timeout, maxOutputChars, environment, null);
    }

    /** 兼容旧构造器，并可选地指定完整输出 artifact 路径。 */
    public ProcessExecutionRequest(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputChars,
            Path outputArtifactPath) {
        this(command, workingDirectory, timeout, maxOutputChars, Map.of(), outputArtifactPath);
    }

    public ProcessExecutionRequest {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(timeout, "timeout");
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        if (command.isEmpty() || command.stream().anyMatch(part -> part == null || part.isBlank())) {
            throw new IllegalArgumentException("command must contain non-blank arguments");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxOutputChars < 1) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        command = List.copyOf(command);
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
        outputArtifactPath = outputArtifactPath == null ? null : outputArtifactPath.toAbsolutePath().normalize();
    }
}
