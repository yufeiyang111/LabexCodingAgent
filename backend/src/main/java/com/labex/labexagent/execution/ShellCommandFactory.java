package com.labex.labexagent.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the exact argv used to invoke the selected worker shell.
 * A complete command remains one payload so Bash or PowerShell owns its syntax.
 */
public final class ShellCommandFactory {
    private ShellCommandFactory() {
    }

    public static List<String> create(WorkerShellDescriptor descriptor, String command) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        List<String> argv = new ArrayList<>(descriptor.prefix().size() + 2);
        argv.add(descriptor.executable());
        argv.addAll(descriptor.prefix());
        argv.add(command);
        return List.copyOf(argv);
    }

    /**
     * Renders server-selected argv as one quoted Shell payload.
     * This keeps run_tests on the shared Shell executor instead of a second direct argv path.
     */
    public static String renderArguments(WorkerShellDescriptor descriptor, List<String> arguments) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException("arguments are required");
        }
        if (arguments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("arguments must not contain null values");
        }
        String rendered = arguments.stream()
                .map(argument -> descriptor.isPowerShell() ? quotePowerShell(argument) : quotePosix(argument))
                .collect(Collectors.joining(" "));
        // PowerShell parses a quoted executable as a string expression. The invocation operator preserves argv semantics.
        return descriptor.isPowerShell() ? "& " + rendered : rendered;
    }

    private static String quotePosix(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
