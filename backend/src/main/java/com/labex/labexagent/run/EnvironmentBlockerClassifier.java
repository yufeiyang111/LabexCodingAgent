package com.labex.labexagent.run;

import com.labex.labexagent.tool.ToolResult;
import java.util.Locale;
import java.util.Optional;

/** 识别依赖下载、DNS 和网络基础设施失败，禁止 Agent 把环境故障当成代码故障反复修改。 */
public final class EnvironmentBlockerClassifier {
    private EnvironmentBlockerClassifier() { }

    public static Optional<Blocker> classify(String toolName, ToolResult result) {
        if (result == null || result.isSuccess() || !isDependencyExecutionTool(toolName)) {
            return Optional.empty();
        }
        String output = result.getContent() == null ? "" : result.getContent().toLowerCase(Locale.ROOT);
        // 熔断器返回的结构化结果不再包含原始 DNS 文本，必须仍然被外层循环识别为终态阻塞
        if (output.contains("failure_code=environment_blocked")
                || output.contains("blockercode=environment_blocked")) {
            return Optional.of(new Blocker("ENVIRONMENT_BLOCKED",
                    "同一外部环境阻塞已经被熔断；请先恢复环境或显式切换到离线验证策略"));
        }
        if (containsAny(output, "unknown host", "could not resolve host", "temporary failure in name resolution",
                "getaddrinfo", "enotfound", "name or service not known", "unknownhostexception")) {
            return Optional.of(new Blocker("DNS_UNAVAILABLE",
                    "依赖仓库域名无法解析，请先恢复 DNS/网络后再重试。"));
        }
        if (containsAny(output, "non-resolvable parent pom", "could not transfer artifact",
                "failed to read artifact descriptor", "failure to transfer")) {
            return Optional.of(new Blocker("DEPENDENCY_RESOLUTION_FAILED",
                    "Maven/npm 依赖无法解析；先检查仓库地址、网络和本地缓存，不要继续修改项目源码。"));
        }
        if (containsAny(output, "network is unreachable", "connection timed out", "connect timed out",
                "connection refused", "failed to connect", "connection reset", "sockettimeoutexception")) {
            return Optional.of(new Blocker("NETWORK_UNAVAILABLE",
                    "依赖仓库不可达，请先恢复网络连接后再重试。"));
        }
        return Optional.empty();
    }

    /** 仅把明确的 DNS、依赖下载或网络连接故障升级为一次性网络重试审批。 */
    public static boolean isNetworkRetryCandidate(String toolName, ToolResult result) {
        return classify(toolName, result)
                .map(blocker -> switch (blocker.code()) {
                    case "DNS_UNAVAILABLE", "DEPENDENCY_RESOLUTION_FAILED", "NETWORK_UNAVAILABLE" -> true;
                    default -> false;
                })
                .orElse(false);
    }

    private static boolean isDependencyExecutionTool(String toolName) {
        if (toolName == null) return false;
        return switch (toolName.trim().toLowerCase(Locale.ROOT)) {
            case "run_tests", "run_command", "bash", "shell" -> true;
            default -> false;
        };
    }

    private static boolean containsAny(String content, String... markers) {
        for (String marker : markers) if (content.contains(marker)) return true;
        return false;
    }

    public record Blocker(String code, String detail) { }
}