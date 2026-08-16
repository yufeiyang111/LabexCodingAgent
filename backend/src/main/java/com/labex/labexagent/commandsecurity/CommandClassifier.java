package com.labex.labexagent.commandsecurity;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Command classification is a control-plane decision only; execution remains inside SandboxWorker.
 * The explicit direct/safe profile retains the legacy small grammar. The default Labex standard profile
 * accepts complete Bash/PowerShell syntax and only blocks control-plane escape or host-danger intent.
 * Network access is enabled by default in the isolated Worker; only destructive operations
 * (bulk deletion, git history/working-tree overwrite, force push) require approval.
 */
@Service
public final class CommandClassifier {
    public static final String POLICY_VERSION = "command-policy-v3";
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern WINDOWS_VARIABLE = Pattern.compile("%[^%\\s]+%", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp|ssh)://");
    private static final Pattern BASE64 = Pattern.compile("(?i)\\b(?:base64|certutil)\\b|(?:frombase64string|decode64)");
    private static final Pattern SHELL_COMMAND = Pattern.compile("(?i)^(?:/usr/bin/)?(?:sh|bash|zsh|dash|cmd(?:\\.exe)?|powershell(?:\\.exe)?|pwsh)(?:\\s|$)");
    private static final Pattern SHELL_COMMAND_FLAG = Pattern.compile("(?i)(?:^|\\s)-(?:c|command)(?:\\s|$)|(?:^|\\s)/(?:c)(?:\\s|$)");
    private static final Pattern POWERSHELL_ENCODED = Pattern.compile("(?i)(?:^|\\s)-(?:enc|encodedcommand)(?:\\s|$)");
    private static final Pattern QUOTING_OR_ESCAPING = Pattern.compile("['\"\\\\^]");
    private static final Pattern UNSAFE_ARGUMENT = Pattern.compile("[|;<>`$&(){}\\[\\]*?!~]");
    private static final Pattern DESTRUCTIVE_SHELL_SEGMENT = Pattern.compile(
            "(?is)(?:^|&&|\\|\\||[;\\n])\\s*(?:sudo\\s+)?(?:rm|del|rmdir|rd|truncate|drop|delete|flushall|flushdb)\\b");
    private static final Pattern DESTRUCTIVE_GIT_SEGMENT = Pattern.compile(
            "(?is)(?:^|&&|\\|\\||[;\\n])\\s*git\\s+(?:reset\\s+--hard|clean\\b|checkout\\s+--|rm\\b|stash\\s+drop)");
    private static final Pattern GIT_FORCE_PUSH_FLAG = Pattern.compile("(?m)(^|\\s)(--force|-f)(\\s|$)");
    private static final Set<String> NETWORK_EXECUTABLES = Set.of(
            "curl", "wget", "invoke-webrequest", "invoke-restmethod", "scp", "sftp", "rsync",
            "ssh", "ftp", "telnet", "nc", "ncat", "netcat", "ping", "tracert", "traceroute",
            "nslookup", "dig", "host");
    private static final Set<String> HARD_BLOCKED_EXECUTABLES = Set.of(
            "shutdown", "reboot", "mkfs", "diskpart", "format", "cipher");
    private static final Set<String> READ_ONLY_EXECUTABLES = Set.of(
            "cat", "echo", "type", "dir", "findstr", "git", "grep", "ls", "pwd", "where", "which");
    private static final Set<String> WORKSPACE_EXECUTABLES = Set.of(
            "touch", "mkdir", "mv", "move", "cp", "copy", "chmod", "chown", "sed", "perl",
            "python", "python3", "node", "java", "npm", "pip", "mvn", "./gradlew", "gradle",
            "pytest", "git");
    private static final Set<String> DESTRUCTIVE_EXECUTABLES = Set.of(
            "rm", "del", "rmdir", "rd", "truncate", "drop", "delete", "flushall", "flushdb", "clear");

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
        boolean realShell = isRealShellRequest(request);
        if (command.isBlank()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.EMPTY_COMMAND, CommandRiskClass.BLOCKED, normalized);
        }
        if (hasForbiddenControlCharacter(command, realShell)) {
            return result(CommandDecision.BLOCK, CommandReasonCode.UNKNOWN_CONTROL_CHARACTER, CommandRiskClass.BLOCKED, normalized);
        }
        if (isPromptInjection(lower)) {
            return result(CommandDecision.BLOCK, CommandReasonCode.PROMPT_INJECTION, CommandRiskClass.BLOCKED, normalized);
        }
        if (realShell) {
            return classifyRealShell(request, normalized, command, lower);
        }
        return classifyDirect(request, normalized, command, lower);
    }

    private CommandClassification classifyRealShell(
            CommandRequest request, NormalizedCommand normalized, String command, String lower) {
        if (POWERSHELL_ENCODED.matcher(command).find()) {
            return result(CommandDecision.BLOCK, CommandReasonCode.POWERSHELL_ENCODED_COMMAND,
                    CommandRiskClass.BLOCKED, normalized);
        }
        if (containsHostDanger(lower)) {
            return result(CommandDecision.BLOCK, CommandReasonCode.HARD_BLOCKED_COMMAND,
                    CommandRiskClass.BLOCKED, normalized);
        }
        if (isDestructiveShell(lower)) {
            return result(CommandDecision.REQUIRE_APPROVAL, CommandReasonCode.MUTATING_COMMAND,
                    CommandRiskClass.MUTATING, normalized);
        }
        // 网络访问默认开启：curl/wget/pip/npm/mvn/git fetch 等网络命令直接执行，不再逐条审批。
        // 其余普通 shell 命令由隔离 Worker 直接执行，不再做语法策略失败。
        return result(CommandDecision.ALLOW, CommandReasonCode.SAFE_SHELL_COMMAND, CommandRiskClass.SAFE, normalized);
    }

    private CommandClassification classifyDirect(
            CommandRequest request, NormalizedCommand normalized, String command, String lower) {
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
            // 网络访问默认开启：URL 形式的命令直接执行，不再逐条审批。
            return result(CommandDecision.ALLOW, CommandReasonCode.NETWORK_URL, CommandRiskClass.SAFE, normalized);
        }
        String networkExecutable = firstToken(lower);
        if (NETWORK_EXECUTABLES.contains(networkExecutable) || request.networkRequested()) {
            return result(CommandDecision.ALLOW, CommandReasonCode.NETWORK_COMMAND, CommandRiskClass.SAFE, normalized);
        }
        if (HARD_BLOCKED_EXECUTABLES.contains(networkExecutable) || containsHostDanger(lower)) {
            return result(CommandDecision.BLOCK, CommandReasonCode.HARD_BLOCKED_COMMAND, CommandRiskClass.BLOCKED, normalized);
        }
        if (isDestructive(networkExecutable, lower)) {
            return result(CommandDecision.REQUIRE_APPROVAL, CommandReasonCode.MUTATING_COMMAND, CommandRiskClass.MUTATING, normalized);
        }
        if (isReadOnly(networkExecutable, lower) || WORKSPACE_EXECUTABLES.contains(networkExecutable)) {
            return result(CommandDecision.ALLOW, CommandReasonCode.SAFE_DIRECT_COMMAND, CommandRiskClass.SAFE, normalized);
        }
        // 未知命令不再拦截：由隔离 Worker 直接执行并返回结果，模型自行解读。
        return result(CommandDecision.ALLOW, CommandReasonCode.UNRECOGNIZED_COMMAND, CommandRiskClass.SAFE, normalized);
    }

    private boolean isRealShellRequest(CommandRequest request) {
        if (request == null || "safe".equalsIgnoreCase(request.sandboxProfile())) {
            return false;
        }
        String shell = request.shell();
        return shell != null && !shell.isBlank() && !"direct".equalsIgnoreCase(shell);
    }

    private boolean hasForbiddenControlCharacter(String command, boolean realShell) {
        return realShell ? command.indexOf('\0') >= 0 : CONTROL_CHARACTER.matcher(command).find();
    }

    private boolean containsHostDanger(String command) {
        return command.contains("/etc/passwd") || command.contains("/etc/shadow")
                || command.contains(".ssh") || command.contains(".aws/credentials")
                || command.contains("169.254.169.254") || command.contains("metadata.google.internal")
                || HARD_BLOCKED_EXECUTABLES.contains(firstToken(command));
    }

    private boolean isDestructiveShell(String command) {
        return DESTRUCTIVE_SHELL_SEGMENT.matcher(command).find()
                || DESTRUCTIVE_GIT_SEGMENT.matcher(command).find()
                || isForcePush(command)
                || command.matches("(?is).*?(?:^|&&|\\|\\||[;\\n])\\s*docker\\b.*");
    }

    private boolean isForcePush(String command) {
        if (!command.contains("git push") || command.contains("--force-with-lease")) {
            return false;
        }
        return GIT_FORCE_PUSH_FLAG.matcher(command).find();
    }

    private boolean isPromptInjection(String command) {
        return command.contains("ignore previous") || command.contains("ignore all previous")
                || command.contains("system prompt") && command.contains("reveal");
    }

    private boolean containsShellOperator(String command) {
        return command.indexOf('|') >= 0 || command.indexOf(';') >= 0 || command.contains("&&") || command.contains("||") || command.indexOf('&') >= 0;
    }

    private String firstToken(String command) {
        String trimmed = command == null ? "" : command.trim();
        int separator = trimmed.indexOf(' ');
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }

    private boolean isDestructive(String executable, String command) {
        if ("git".equals(executable)) {
            return command.startsWith("git reset --hard")
                    || command.startsWith("git clean ")
                    || command.startsWith("git checkout --")
                    || command.startsWith("git rm ")
                    || command.startsWith("git stash drop")
                    || isForcePush(command);
        }
        return DESTRUCTIVE_EXECUTABLES.contains(executable) || "docker".equals(executable);
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

    private CommandClassification result(CommandDecision decision, CommandReasonCode reason,
                                         CommandRiskClass risk, NormalizedCommand normalized) {
        return new CommandClassification(decision, reason, risk, POLICY_VERSION,
                normalized.normalizerVersion(), normalized);
    }
}
