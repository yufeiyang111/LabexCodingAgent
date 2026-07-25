package com.labex.labexagent.commandsecurity;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the restricted direct-command grammar after classification. Shell quoting, escaping,
 * expansion, operators, and workspace-escaping path operands are rejected instead of interpreted.
 */
public final class DirectCommandTokenizer {
    private DirectCommandTokenizer() {
    }

    public static List<String> tokenize(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        List<String> arguments = new ArrayList<>();
        StringBuilder argument = new StringBuilder();
        boolean inWhitespace = true;
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (Character.isWhitespace(character)) {
                if (!inWhitespace) {
                    addArgument(arguments, argument);
                    argument.setLength(0);
                    inWhitespace = true;
                }
                continue;
            }
            if (isUnsupportedSyntax(character)) {
                throw new IllegalArgumentException("command contains unsupported shell syntax");
            }
            argument.append(character);
            inWhitespace = false;
        }
        if (!inWhitespace) {
            addArgument(arguments, argument);
        }
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        return List.copyOf(arguments);
    }

    private static void addArgument(List<String> arguments, StringBuilder argument) {
        String value = argument.toString();
        if (isWorkspaceEscapingPath(value)) {
            throw new IllegalArgumentException("command contains an unsafe path operand");
        }
        arguments.add(value);
    }

    private static boolean isWorkspaceEscapingPath(String value) {
        String normalized = value.replace('\\', '/');
        return normalized.equals("..") || normalized.startsWith("../") || normalized.startsWith("/")
                || normalized.matches("(?i)^[a-z]:.*");
    }

    private static boolean isUnsupportedSyntax(char character) {
        return character == '\'' || character == '"' || character == '\\'
                || character == '|' || character == ';' || character == '&'
                || character == '<' || character == '>' || character == '`'
                || character == '$' || character == '(' || character == ')';
    }
}
