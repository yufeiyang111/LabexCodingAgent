package com.labex.labexagent.commandsecurity;

import java.util.ArrayList;
import java.util.List;
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
public class CommandClassifier {
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
    /**
     * 只读 shell 载荷的判定白名单：仅用于跳过工作区快照，不参与审批。
     * 任何不在白名单内的可执行文件、任何写入重定向或内联执行都判定为“会修改”。
     */
    private static final Set<String> READ_ONLY_SHELL_EXECUTABLES = Set.of(
            "cat", "cd", "cut", "diff", "du", "echo", "find", "git", "grep", "head",
            "ls", "pwd", "sort", "stat", "tail", "true", "type", "uniq", "wc", "where", "which");
    private static final List<String> MUTATING_SHELL_MARKERS = List.of(
            ">", "<", "$(", "`", "${", "-delete", "-fprint", "-fls",
            "xargs", "tee", "--output");
    /**
     * 写 `/dev/null` 和文件描述符 duplicated（{@code 2>&1}、{@code >&2}）不落盘，可在判定前剥掉；
     * 其余重定向一律按写盘处理。
     */
    private static final Pattern NULL_OR_FD_REDIRECT = Pattern.compile(
            "\\d?>>?\\s*/dev/null(?![\\w.\\-/])|\\d?>&\\d|\\d?>&-");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 命令替换允许的最大嵌套层数，超过按“会修改”处理。 */
    private static final int MAX_SUBSTITUTION_DEPTH = 4;
    private static final Set<String> GIT_READ_ONLY_SUBCOMMANDS = Set.of(
            "status", "diff", "log", "show", "rev-parse", "ls-files", "ls-tree", "cat-file",
            "describe", "shortlog", "blame", "grep");
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

    /**
     * 判断一条完整 shell 载荷是否只读，仅用于决定 shell 工具调用是否需要前后各做一次工作区快照。
     *
     * <p>失败方向固定为“当作会修改”：识别不了的结构一律返回 false，代价只是多记一次快照，绝不会
     * 漏掉一次真实改动。写入重定向、白名单外的可执行文件、以及通过 `find` 执行内联命令的载荷都直接
     * 判定为非只读；命令替换 `$(...)` / 反引号按同一规则递归判定内层命令。
     */
    public static boolean readOnlyShellPayload(String command) {
        return readOnlyShellPayload(command, 0);
    }

    private static boolean readOnlyShellPayload(String command, int depth) {
        if (command == null || command.isBlank() || depth > MAX_SUBSTITUTION_DEPTH) {
            return false;
        }
        String payload = command.replace("\r\n", "\n").replace('\r', '\n');
        List<String> substitutions = new ArrayList<>();
        String outer = stripCommandSubstitutions(payload, substitutions);
        if (outer == null) {
            return false;
        }
        for (String inner : substitutions) {
            if (!readOnlyShellPayload(inner, depth + 1)) {
                return false;
            }
        }
        String lower = NULL_OR_FD_REDIRECT.matcher(outer.toLowerCase(Locale.ROOT)).replaceAll(" ");
        for (String marker : MUTATING_SHELL_MARKERS) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        List<String> segments = splitUnquoted(lower);
        if (segments == null) {
            return false;
        }
        for (String segment : segments) {
            if (!isReadOnlyShellSegment(segment, depth)) {
                return false;
            }
        }
        return true;
    }

    /** 按未加引号的分隔符切段；引号不配对时返回 null。 */
    private static List<String> splitUnquoted(String payload) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        int index = 0;
        while (index < payload.length()) {
            char value = payload.charAt(index);
            if (value == '\\' && index + 1 < payload.length() && !inSingle) {
                current.append(value).append(payload.charAt(index + 1));
                index += 2;
                continue;
            }
            if (!inDouble && value == '\'') {
                inSingle = !inSingle;
                current.append(value);
                index++;
                continue;
            }
            if (!inSingle && value == '"') {
                inDouble = !inDouble;
                current.append(value);
                index++;
                continue;
            }
            if (!inSingle && !inDouble) {
                if (value == ';' || value == '\n') {
                    segments.add(current.toString());
                    current.setLength(0);
                    index++;
                    continue;
                }
                if (value == '|' || value == '&') {
                    segments.add(current.toString());
                    current.setLength(0);
                    boolean doubled = index + 1 < payload.length() && payload.charAt(index + 1) == value;
                    index += doubled ? 2 : 1;
                    continue;
                }
            }
            current.append(value);
            index++;
        }
        if (inSingle || inDouble) {
            return null;
        }
        segments.add(current.toString());
        return segments;
    }

    /**
     * 把 {@code $(...)} 与反引号内容整段挖掉并按出现顺序收进 {@code out}，返回剩余外壳载荷。
     * 括号或反引号不配对时返回 null。单引号内的替换在 bash 里不会执行，这里同样按会执行处理，
     * 方向仍是“更保守”。
     */
    private static String stripCommandSubstitutions(String payload, List<String> out) {
        StringBuilder stripped = new StringBuilder(payload.length());
        int index = 0;
        while (index < payload.length()) {
            char current = payload.charAt(index);
            if (current == '$' && index + 1 < payload.length() && payload.charAt(index + 1) == '(') {
                int depth = 0;
                int cursor = index + 1;
                while (cursor < payload.length()) {
                    char inner = payload.charAt(cursor);
                    if (inner == '(') {
                        depth++;
                    } else if (inner == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                    cursor++;
                }
                if (cursor >= payload.length()) {
                    return null;
                }
                out.add(payload.substring(index + 2, cursor));
                stripped.append(' ');
                index = cursor + 1;
                continue;
            }
            if (current == '`') {
                int close = payload.indexOf('`', index + 1);
                if (close < 0) {
                    return null;
                }
                out.add(payload.substring(index + 1, close));
                stripped.append(' ');
                index = close + 1;
                continue;
            }
            stripped.append(current);
            index++;
        }
        return stripped.toString();
    }

    private static boolean isReadOnlyShellSegment(String segment, int depth) {
        String trimmed = segment == null ? "" : segment.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String[] tokens = WHITESPACE.split(trimmed);
        String executable = unquote(tokens[0]).toLowerCase(Locale.ROOT);
        if (!READ_ONLY_SHELL_EXECUTABLES.contains(executable)) {
            return false;
        }
        if ("git".equals(executable)) {
            return tokens.length < 2 || GIT_READ_ONLY_SUBCOMMANDS.contains(unquote(tokens[1]).toLowerCase(Locale.ROOT));
        }
        if ("sort".equals(executable)) {
            // sort -o / --output 会覆盖文件，其余用法只读。
            for (String token : tokens) {
                if (unquote(token).toLowerCase(Locale.ROOT).startsWith("-o")) {
                    return false;
                }
            }
        }
        if ("find".equals(executable)) {
            return findIsReadOnly(tokens, depth);
        }
        return true;
    }

    /** find 的写盘 primary 不在白名单；-exec/-ok 的内层命令按同一只读规则递归判定。 */
    private static boolean findIsReadOnly(String[] tokens, int depth) {
        int index = 1;
        while (index < tokens.length) {
            String token = unquote(tokens[index]).toLowerCase(Locale.ROOT);
            boolean execPrimary = "-exec".equals(token) || "-execdir".equals(token)
                    || "-ok".equals(token) || "-okdir".equals(token);
            if (!execPrimary) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < tokens.length) {
                String candidate = unquote(tokens[end]);
                if (";".equals(candidate) || "+".equals(candidate)) {
                    break;
                }
                end++;
            }
            if (end >= tokens.length || end == index + 1) {
                return false;
            }
            StringBuilder inner = new StringBuilder();
            for (int cursor = index + 1; cursor < end; cursor++) {
                if (inner.length() > 0) {
                    inner.append(' ');
                }
                inner.append(unquote(tokens[cursor]));
            }
            if (!readOnlyShellPayload(inner.toString(), depth + 1)) {
                return false;
            }
            index = end + 1;
        }
        return true;
    }

    private static String unquote(String token) {
        if (token == null || token.length() < 2) {
            return token == null ? "" : token;
        }
        char first = token.charAt(0);
        if ((first == '"' || first == '\'') && first == token.charAt(token.length() - 1)) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    private CommandClassification result(CommandDecision decision, CommandReasonCode reason,
                                         CommandRiskClass risk, NormalizedCommand normalized) {
        boolean readOnlyShell = decision == CommandDecision.ALLOW && risk == CommandRiskClass.SAFE
                && readOnlyShellPayload(normalized.canonicalCommand());
        return new CommandClassification(decision, reason, risk, readOnlyShell, POLICY_VERSION,
                normalized.normalizerVersion(), normalized);
    }
}
