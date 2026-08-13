package com.labex.labexagent.commandsecurity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将常见的 {@code cd module && direct-command} 收敛为 worker 工作目录与直接 argv。
 * 只接受单个安全相对目录前缀，不解析任意 shell 语法。
 */
public final class DirectCommandWorkingDirectory {
    private static final Pattern CD_PREFIX = Pattern.compile(
            "^\\s*cd\\s+([^\\s]+)\\s+&&\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    private DirectCommandWorkingDirectory() {
    }

    public static Normalized normalize(String command, String workingDirectory) {
        String directCommand = command == null ? "" : command.trim();
        String baseDirectory = normalizeBaseDirectory(workingDirectory);
        Matcher matcher = CD_PREFIX.matcher(directCommand);
        if (!matcher.matches()) {
            return new Normalized(directCommand, baseDirectory, false);
        }

        String requestedDirectory = normalizeRelativeDirectory(matcher.group(1));
        String remainingCommand = matcher.group(2).trim();
        if (remainingCommand.isBlank()) {
            throw new IllegalArgumentException("direct command after working directory is required");
        }
        String combined = ".".equals(baseDirectory)
                ? requestedDirectory
                : normalizeRelativeDirectory(baseDirectory + "/" + requestedDirectory);
        return new Normalized(remainingCommand, combined, true);
    }

    private static String normalizeBaseDirectory(String value) {
        String normalized = value == null ? "." : value.trim().replace('\\', '/');
        return normalized.isBlank() ? "." : normalized;
    }

    private static String normalizeRelativeDirectory(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/")
                || normalized.matches("(?i)^[a-z]:.*")) {
            throw new IllegalArgumentException("working directory must be a safe relative project path");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || !SAFE_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("working directory must be a safe relative project path");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            return ".";
        }
        return String.join("/", segments);
    }

    public record Normalized(String command, String workingDirectory, boolean rewritten) {
        public Normalized {
            command = command == null ? "" : command.trim();
            workingDirectory = workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory;
        }
    }
}
