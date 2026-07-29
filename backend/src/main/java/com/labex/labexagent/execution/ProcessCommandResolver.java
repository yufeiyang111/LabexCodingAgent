package com.labex.labexagent.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 在 Windows 上把 npm/mvn 等命令映射到可由 ProcessBuilder 直接启动的脚本文件。 */
public final class ProcessCommandResolver {
    private ProcessCommandResolver() { }

    public static List<String> resolve(List<String> command) {
        if (command == null || command.isEmpty() || !isWindows()) {
            return command == null ? List.of() : List.copyOf(command);
        }
        String executable = command.get(0);
        if (executable == null || executable.isBlank()
                || executable.contains("\\") || executable.contains("/")
                || executable.contains(".")) {
            return List.copyOf(command);
        }
        String suffix = switch (executable.toLowerCase(Locale.ROOT)) {
            case "npm", "npx", "pnpm", "yarn", "mvn" -> ".cmd";
            case "gradle", "gradlew" -> ".bat";
            default -> "";
        };
        if (suffix.isBlank()) return List.copyOf(command);
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, executable + suffix);
        return List.copyOf(resolved);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
