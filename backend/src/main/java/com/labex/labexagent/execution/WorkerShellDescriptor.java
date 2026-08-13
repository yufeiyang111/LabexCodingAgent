package com.labex.labexagent.execution;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Worker 对真实 Shell 执行能力的只读描述。
 * 权限决策不属于该对象；它只负责告诉 Tool 应使用哪个 Shell、参数前缀和工作区别名。
 */
public record WorkerShellDescriptor(
        String platform,
        String shellName,
        String executable,
        List<String> prefix,
        String workspaceRoot,
        boolean networkEnabled) {

    public WorkerShellDescriptor {
        platform = requireText(platform, "platform");
        shellName = requireText(shellName, "shellName").toLowerCase(Locale.ROOT);
        executable = requireText(executable, "executable");
        workspaceRoot = requireText(workspaceRoot, "workspaceRoot");
        prefix = prefix == null ? List.of() : List.copyOf(prefix);
        if (prefix.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("shell prefix must contain non-blank arguments");
        }
    }

    public static WorkerShellDescriptor bash(
            String platform, String executable, String workspaceRoot, boolean networkEnabled) {
        return new WorkerShellDescriptor(platform, "bash", executable,
                List.of("--noprofile", "--norc", "-lc"), workspaceRoot, networkEnabled);
    }

    public static WorkerShellDescriptor powerShell(
            String platform, String executable, String workspaceRoot, boolean networkEnabled) {
        return new WorkerShellDescriptor(platform, "powershell", executable,
                List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-Command"), workspaceRoot, networkEnabled);
    }

    public boolean isPowerShell() {
        return "powershell".equals(shellName) || "pwsh".equals(shellName);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}