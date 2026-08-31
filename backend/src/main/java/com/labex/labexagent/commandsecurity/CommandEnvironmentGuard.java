package com.labex.labexagent.commandsecurity;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.WorkerShellExecutor;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shell 执行前的只读开发环境检查。它不是 Agent Tool，不会出现在模型工具列表中。
 */
public final class CommandEnvironmentGuard {
    private static final int PROBE_OUTPUT_LIMIT = 4_096;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, List<String>> TOOL_REQUIREMENTS = requirements();

    private final SandboxWorker worker;
    private final WorkerShellExecutor shellExecutor;

    public CommandEnvironmentGuard(SandboxWorker worker) {
        this.worker = worker;
        this.shellExecutor = new WorkerShellExecutor(worker);
    }

    public CheckResult check(WorkerRunSpec run, WorkerShellDescriptor descriptor, String command, Path workdir) {
        List<String> required = requiredTools(command, descriptor);
        if (required.isEmpty()) {
            return CheckResult.createAvailable();
        }
        String probe = probeCommand(required, descriptor);
        WorkerShellExecutor.PreparedExecution prepared = shellExecutor.prepare(
                descriptor, probe, workdir, PROBE_TIMEOUT, PROBE_OUTPUT_LIMIT, null);
        ProcessExecutionResult result = shellExecutor.execute(run, prepared, CancellationToken.none());
        if (result == null || result.status() != ExecutionStatus.SUCCEEDED) {
            // 探测本身失败时不阻断原命令；真实命令仍会返回可诊断的执行错误。
            return CheckResult.createProbeUnavailable();
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String line : result.output().split("\\R")) {
            if (line.startsWith("missing:")) {
                missing.add(line.substring("missing:".length()).trim());
            }
        }
        return missing.isEmpty() ? CheckResult.createAvailable() : CheckResult.createMissing(List.copyOf(missing));
    }

    static List<String> requiredTools(String command, WorkerShellDescriptor descriptor) {
        if (command == null || command.isBlank() || descriptor == null) {
            return List.of();
        }
        String lower = command.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : TOOL_REQUIREMENTS.entrySet()) {
            if (Pattern.compile("(?<![\\w.-])" + Pattern.quote(entry.getKey()) + "(?![\\w.-])")
                    .matcher(lower).find()) {
                result.addAll(entry.getValue());
            }
        }
        return List.copyOf(result);
    }

    private String probeCommand(List<String> required, WorkerShellDescriptor descriptor) {
        if (descriptor.isPowerShell()) {
            return required.stream()
                    .map(tool -> "if (-not (Get-Command " + tool + " -ErrorAction SilentlyContinue)) { Write-Output 'missing:" + tool + "' }")
                    .reduce((left, right) -> left + "; " + right)
                    .orElse(":");
        }
        return "for tool in " + String.join(" ", required)
                + "; do command -v \"$tool\" >/dev/null 2>&1 || printf 'missing:%s\\n' \"$tool\"; done; exit 0";
    }

    private static Map<String, List<String>> requirements() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("npm", List.of("node", "npm"));
        values.put("npx", List.of("node", "npm", "npx"));
        values.put("yarn", List.of("node", "yarn"));
        values.put("pnpm", List.of("node", "pnpm"));
        values.put("node", List.of("node"));
        values.put("python3", List.of("python3"));
        values.put("python", List.of("python"));
        values.put("pip3", List.of("python3", "pip3"));
        values.put("pip", List.of("python", "pip"));
        values.put("pytest", List.of("python3", "pytest"));
        values.put("mvn", List.of("java", "mvn"));
        values.put("gradle", List.of("java", "gradle"));
        values.put("java", List.of("java"));
        values.put("go", List.of("go"));
        values.put("cargo", List.of("rustc", "cargo"));
        values.put("rustc", List.of("rustc"));
        return Collections.unmodifiableMap(values);
    }

    public record CheckResult(boolean available, List<String> missingTools, boolean probeUnavailable) {
        public static CheckResult createAvailable() { return new CheckResult(true, List.of(), false); }
        public static CheckResult createMissing(List<String> tools) { return new CheckResult(false, tools, false); }
        public static CheckResult createProbeUnavailable() { return new CheckResult(true, List.of(), true); }

        public String failureMessage() {
            return "environment_status=missing\n"
                    + "missing_tools=" + String.join(", ", missingTools) + "\n"
                    + "command_not_executed=true\n"
                    + "hint=当前 Worker 环境缺少上述开发工具，请联系管理员更新 Worker 镜像。";
        }
    }
}



