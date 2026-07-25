package com.labex.labexagent.commandsecurity;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure fail-closed policy for a deliberately small direct-command grammar. It never parses or
 * executes shell grammar: unsupported executables, quoting, escaping, control characters, and
 * shell syntax are blocked rather than guessed at.
 */
public final class CommandClassifier {
    public static final String POLICY_VERSION = "command-policy-v1";
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern WINDOWS_VARIABLE = Pattern.compile("%[^%\\s]+%", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp|ssh)://");
    private static final Pattern BASE64 = Pattern.compile("(?i)\\b(?:base64|certutil)\\b|(?:frombase64string|decode64)");
    private static final Pattern SHELL_COMMAND = Pattern.compile("(?i)^(?:/usr/bin/)?(?:sh|bash|zsh|dash|cmd(?:\\.exe)?|powershell(?:\\.exe)?|pwsh)(?:\\s|$)");
    private static final Pattern SHELL_COMMAND_FLAG = Pattern.compile("(?i)(?:^|\\s)-(?:c|command)(?:\\s|$)|(?:^|\\s)/(?:c)(?:\\s|$)");
    private static final Pattern POWERSHELL_ENCODED = Pattern.compile("(?i)(?:^|\\s)-(?:enc|encodedcommand)(?:\\s|$)");
    private static final Pattern QUOTING_OR_ESCAPING = Pattern.compile("['\"\\\\^]");
    private static final Pattern UNSAFE_ARGUMENT = Pattern.compile("[|;<>`$&(){}\\[\\]*?!~]");
    private static final Set<String> NETWORK_EXECUTABLES = Set.of(
            "curl", "wget", "invoke-webrequest", "invoke-restmethod", "scp", "sftp", "rsync",
            "ssh", "ftp", "telnet", "nc", "ncat", "netcat", "ping", "tracert", "traceroute",
            "nslookup", "dig", "host");
    private static final Set<String> HARD_BLOCKED_EXECUTABLES = Set.of(
            "shutdown", "reboot", "mkfs", "diskpart", "format", "cipher");
    private static final Set<String> READ_ONLY_EXECUTABLES = Set.of(
            "cat", "echo", "type", "dir", "findstr", "git", "grep", "ls", "pwd", "where", "which");
    private static final Set<String> MUTATING_EXECUTABLES = Set.of(
            "rm", "del", "rmdir", "rd", "touch", "mkdir", "mv", "move", "cp", "copy",
            "chmod", "chown", "sed", "perl", "python", "python3", "node", "java", "docker", "npm",
            "pip", "mvn", "./gradlew", "gradle", "pytest", "git", "drop", "truncate", "delete", "flushall", "flushdb");

    private final CommandNormalizer normalizer;

    public CommandClassifier() {
        this(new CommandNormalizer());
    }

    public CommandClassifier(CommandNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public CommandClassification classify(CommandRequest request) {
        NormalizedCommand normalized = normalizer.normalize(request);
        String command = normalized.canonicalCommand();
        String lower = command.toLowerCase(Locale.ROOT);
        if (command.isEmpty()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.EMPTY_COMMAND, CommandRiskClass.BLOCKED, normalized);
        }
        if (CONTROL_CHARACTER.matcher(command).find()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.UNKNOWN_CONTROL_CHARACTER, CommandRiskClass.BLOCKED, normalized);
        }
        if (isPromptInjection(lower)) {
            return result(CommandDecision.BLOCK, CommandReasonCode.PROMPT_INJECTION, CommandRiskClass.BLOCKED, normalized);
        }
        if (containsShellOperator(command)) {
            return result(CommandDecision.BLOCK, CommandReasonCode.SHELL_OPERATOR, CommandRiskClass.BLOCKED, normalized);
        }
        if (command.indexOf('>') >= 0 || command.indexOf('<') >= 0) {
            return result(CommandDecision.BLOCK, CommandReasonCode.REDIRECTION, CommandRiskClass.BLOCKED, normalized);
        }
        if (command.indexOf('`') >= 0 || command.contains("$(")) {
            return result(CommandDecision.BLOCK, CommandReasonCode.COMMAND_SUBSTITUTION, CommandRiskClass.BLOCKED, normalized);
        }
        if (command.indexOf('$') >= 0) {
            return result(CommandDecision.BLOCK, CommandReasonCode.VARIABLE_EXPANSION, CommandRiskClass.BLOCKED, normalized);
        }
        if (WINDOWS_VARIABLE.matcher(command).find()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.WINDOWS_VARIABLE_EXPANSION, CommandRiskClass.BLOCKED, normalized);
        }
        if (BASE64.matcher(command).find()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.ENCODED_EXECUTION, CommandRiskClass.BLOCKED, normalized);
        }
        String executable = firstToken(lower);
        if (SHELL_COMMAND.matcher(command).find()) {
            if (POWERSHELL_ENCODED.matcher(command).find()) {
                return result(CommandDecision.BLOCK, CommandReasonCode.POWERSHELL_ENCODED_COMMAND, CommandRiskClass.BLOCKED, normalized);
            }
            if (SHELL_COMMAND_FLAG.matcher(command).find()) {
                return result(CommandDecision.BLOCK, CommandReasonCode.SHELL_COMMAND_STRING, CommandRiskClass.BLOCKED, normalized);
            }
            if (executable.contains("powershell") || "pwsh".equals(executable)) {
                return result(CommandDecision.BLOCK, CommandReasonCode.POWERSHELL_COMMAND, CommandRiskClass.BLOCKED, normalized);
            }
            return result(CommandDecision.BLOCK, CommandReasonCode.UNSUPPORTED_SYNTAX, CommandRiskClass.BLOCKED, normalized);
        }
        if (QUOTING_OR_ESCAPING.matcher(command).find()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.QUOTE_SPLIT_EXECUTABLE, CommandRiskClass.BLOCKED, normalized);
        }
        if (UNSAFE_ARGUMENT.matcher(command).find()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.UNSUPPORTED_SYNTAX, CommandRiskClass.BLOCKED, normalized);
        }
        if (URL.matcher(command).find()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.NETWORK_URL, CommandRiskClass.BLOCKED, normalized);
        }

        if (NETWORK_EXECUTABLES.contains(executable) || request.networkRequested() || isNetworkCapableCommand(executable, lower)) {
            return result(CommandDecision.BLOCK, CommandReasonCode.NETWORK_COMMAND, CommandRiskClass.BLOCKED, normalized);
        }
        if (HARD_BLOCKED_EXECUTABLES.contains(executable) || lower.contains("/etc/passwd") || lower.contains("/etc/shadow")
                || lower.contains(".ssh") || lower.contains(".aws/credentials") || lower.contains("169.254.169.254")
                || lower.contains("metadata.google.internal")) {
            return result(CommandDecision.BLOCK, CommandReasonCode.HARD_BLOCKED_COMMAND, CommandRiskClass.BLOCKED, normalized);
        }
        if (isMutating(executable, lower)) {
            return result(CommandDecision.REQUIRE_APPROVAL, CommandReasonCode.MUTATING_COMMAND, CommandRiskClass.MUTATING, normalized);
        }
        if (isReadOnly(executable, lower)) {
            return result(CommandDecision.ALLOW, CommandReasonCode.SAFE_DIRECT_COMMAND, CommandRiskClass.SAFE, normalized);
        }
        return result(CommandDecision.BLOCK, CommandReasonCode.UNSUPPORTED_SYNTAX, CommandRiskClass.BLOCKED, normalized);
    }

    private boolean isPromptInjection(String command) {
        return command.contains("ignore previous") || command.contains("ignore all previous")
                || command.contains("system prompt") && command.contains("reveal");
    }

    private boolean containsShellOperator(String command) {
        return command.indexOf('|') >= 0 || command.indexOf(';') >= 0 || command.contains("&&") || command.contains("||") || command.indexOf('&') >= 0;
    }

    private String firstToken(String command) {
        int separator = command.indexOf(' ');
        return separator < 0 ? command : command.substring(0, separator);
    }

    private boolean isNetworkCapableCommand(String executable, String command) {
        return ("git".equals(executable) && (command.startsWith("git fetch") || command.startsWith("git clone")
                || command.startsWith("git pull") || command.startsWith("git push")))
                || ("npm".equals(executable) && (command.startsWith("npm install") || command.startsWith("npm publish")))
                || ("pip".equals(executable) && command.startsWith("pip install"))
                || ("mvn".equals(executable) && (command.startsWith("mvn deploy") || command.contains("dependency:get")));
    }

    private boolean isMutating(String executable, String command) {
        if ("npm".equals(executable)) {
            return command.equals("npm test") || command.startsWith("npm test ");
        }
        if ("mvn".equals(executable)) {
            return command.equals("mvn test") || command.startsWith("mvn test ");
        }
        if ("./gradlew".equals(executable) || "gradle".equals(executable)) {
            return command.equals(executable + " test") || command.startsWith(executable + " test ");
        }
        if ("pytest".equals(executable)) {
            return true;
        }
        if ("python".equals(executable) || "python3".equals(executable)) {
            return MUTATING_EXECUTABLES.contains(executable);
        }
        return MUTATING_EXECUTABLES.contains(executable) && !isReadOnly(executable, command);
    }

    private boolean isReadOnly(String executable, String command) {
        if (!READ_ONLY_EXECUTABLES.contains(executable)) {
            return false;
        }
        if ("git".equals(executable)) {
            return command.equals("git status") || command.startsWith("git status ") || command.equals("git diff")
                    || command.startsWith("git diff ") || command.equals("git log") || command.startsWith("git log ");
        }
        if ("npm".equals(executable)) {
            return command.equals("npm test") || command.startsWith("npm test ");
        }
        return true;
    }

    private CommandClassification result(CommandDecision decision, CommandReasonCode reason, CommandRiskClass risk, NormalizedCommand normalized) {
        return new CommandClassification(decision, reason, risk, POLICY_VERSION, normalized.normalizerVersion(), normalized);
    }
}
