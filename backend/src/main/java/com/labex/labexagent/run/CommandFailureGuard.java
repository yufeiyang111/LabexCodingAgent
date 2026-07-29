package com.labex.labexagent.run;

import com.labex.entity.AgentRunArtifact;
import com.labex.labexagent.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 任务级命令失败熔断器，按外部环境根因阻止 Agent 绕过熔断继续申请同类审批。 */
@Service
public class CommandFailureGuard {
    private static final String ARTIFACT_TYPE = "command_failure";
    private final AgentRunArtifactService artifactService;
    private final int maxEnvironmentFailures;
    private final int maxRepeatedFailures;
    private final ConcurrentMap<String, List<Observation>> memory = new ConcurrentHashMap<>();

    @Autowired
    public CommandFailureGuard(AgentRunArtifactService artifactService, AgentRecoveryProperties properties) {
        this.artifactService = artifactService;
        this.maxEnvironmentFailures = properties == null ? 1 : properties.getMaxEnvironmentFailures();
        this.maxRepeatedFailures = properties == null ? 2 : properties.getMaxRepeatedCommandFailures();
    }

    /** 单测及无持久化场景使用的构造器。 */
    public CommandFailureGuard(int maxEnvironmentFailures, int maxRepeatedFailures) {
        this.artifactService = null;
        this.maxEnvironmentFailures = Math.max(1, maxEnvironmentFailures);
        this.maxRepeatedFailures = Math.max(1, maxRepeatedFailures);
    }

    public Decision before(Long taskId, String command, String workingDirectory) {
        if (taskId == null || command == null || command.isBlank()) {
            return Decision.allow();
        }
        String fingerprint = fingerprint(command, workingDirectory);
        String scope = scopeFor(command);
        // 环境阻塞是任务级的根因，而不是某一个精确命令的属性。
        // 例如 mvn test 失败后切换成 mvn compile，不能重新获得一次审批并继续撞同一个 Maven DNS 问题。
        Observation environment = allObservations(taskId).stream()
                .filter(Observation::environmentBlocked)
                .filter(observation -> sameScope(scope, observation.scope()))
                .findFirst()
                .orElse(null);
        if (environment != null) {
            return new Decision(false, "ENVIRONMENT_BLOCKED",
                    "依赖环境被阻塞，已停止同类验证命令的重复审批。请先恢复 DNS/网络或切换到可用的离线/手工验证策略。",
                    Math.max(1, environment.attempts()));
        }

        List<Observation> observations = observations(taskId, fingerprint);
        if (observations.isEmpty()) {
            return Decision.allow();
        }
        int attempts = observations.size();
        if (attempts < maxRepeatedFailures) {
            return Decision.allow(attempts);
        }
        return new Decision(false, "REPEATED_COMMAND_FAILURE",
                "同一命令已经连续失败，已停止重复审批。请先修改代码/命令或切换验证策略后再试。", attempts);
    }

    public void record(Long taskId, String toolName, String command, String workingDirectory, String output) {
        if (taskId == null || command == null || command.isBlank()) {
            return;
        }
        String safeOutput = output == null ? "" : output;
        ToolResult result = ToolResult.failed(safeOutput);
        EnvironmentBlockerClassifier.Blocker blocker = EnvironmentBlockerClassifier.classify(toolName, result).orElse(null);
        String code = blocker == null ? "COMMAND_FAILED" : blocker.code();
        String fingerprint = fingerprint(command, workingDirectory);
        String scope = scopeFor(command);
        Observation observation = new Observation(fingerprint, scope, code, blocker != null, 1);
        memory.computeIfAbsent(key(taskId, fingerprint), ignored -> new ArrayList<>()).add(observation);
        if (artifactService != null) {
            String content = "fingerprint=" + fingerprint + "\nscope=" + scope + "\ncode=" + code
                    + "\nenvironmentBlocked=" + (blocker != null) + "\ntool=" + (toolName == null ? "" : toolName)
                    + "\ncommand=" + command + "\nworkingDirectory=" + (workingDirectory == null ? "." : workingDirectory)
                    + "\ndetail=" + safeOutput.substring(0, Math.min(4_000, safeOutput.length()));
            try {
                artifactService.record(taskId, ARTIFACT_TYPE, fingerprint, content);
            } catch (RuntimeException ignored) {
                // 失败记录不可掩盖原始命令结果；内存计数仍然提供当前进程保护。
            }
        }
    }

    public void reset(Long taskId) {
        if (taskId == null) return;
        memory.keySet().removeIf(key -> key.startsWith(taskId + ":"));
        if (artifactService != null) {
            try {
                artifactService.record(taskId, "command_failure_reset", null,
                        "resetAt=" + java.time.LocalDateTime.now());
            } catch (RuntimeException ignored) {
                // 当前进程内存代际已清除；持久化失败不应阻止用户发起恢复动作。
            }
        }
    }

    public String fingerprintFor(String command, String workingDirectory) {
        return fingerprint(command, workingDirectory);
    }

    private List<Observation> observations(Long taskId, String fingerprint) {
        List<Observation> all = allObservations(taskId);
        List<Observation> result = all.stream()
                .filter(observation -> fingerprint.equals(observation.fingerprint()))
                .toList();
        return result.isEmpty()
                ? new ArrayList<>(memory.getOrDefault(key(taskId, fingerprint), List.of()))
                : new ArrayList<>(result);
    }

    private List<Observation> allObservations(Long taskId) {
        List<Observation> result = new ArrayList<>();
        if (artifactService != null) {
            for (AgentRunArtifact artifact : artifactService.listAll(taskId)) {
                if ("command_failure_reset".equals(artifact.getArtifactType())) {
                    result.clear();
                    continue;
                }
                if (!ARTIFACT_TYPE.equals(artifact.getArtifactType())) continue;
                String content = artifact.getContent() == null ? "" : artifact.getContent();
                String fingerprint = value(content, "fingerprint=");
                if (fingerprint.isBlank()) continue;
                String command = value(content, "command=");
                String scope = value(content, "scope=");
                if (scope.isBlank()) scope = scopeFor(command);
                boolean environment = content.contains("environmentBlocked=true");
                String code = value(content, "code=");
                result.add(new Observation(fingerprint, scope,
                        code.isBlank() ? "COMMAND_FAILED" : code, environment, 1));
            }
        }
        if (result.isEmpty()) {
            memory.forEach((entryKey, values) -> {
                if (entryKey.startsWith(taskId + ":")) result.addAll(values);
            });
        }
        return result;
    }

    private static String value(String content, String prefix) {
        for (String line : content.split("\\R")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private static String key(Long taskId, String fingerprint) { return taskId + ":" + fingerprint; }

    private static String scopeFor(String command) {
        String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("mvn ") || normalized.equals("mvn") || normalized.contains("\\mvn ")
                || normalized.contains("/mvn ")) return "maven";
        if (normalized.startsWith("npm ") || normalized.equals("npm") || normalized.contains("\\npm ")
                || normalized.contains("/npm ")) return "npm";
        if (normalized.startsWith("pnpm ") || normalized.equals("pnpm") || normalized.contains("\\pnpm ")
                || normalized.contains("/pnpm ")) return "pnpm";
        if (normalized.startsWith("yarn ") || normalized.equals("yarn") || normalized.contains("\\yarn ")
                || normalized.contains("/yarn ")) return "yarn";
        return "command";
    }

    private static boolean sameScope(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    private static String fingerprint(String command, String workingDirectory) {
        String value = (command == null ? "" : command.trim()) + "\n"
                + (workingDirectory == null ? "." : workingDirectory.trim());
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder digest = new StringBuilder(64);
            for (byte b : bytes) digest.append(String.format(Locale.ROOT, "%02x", b));
            return digest.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Observation(String fingerprint, String scope, String code,
                               boolean environmentBlocked, int attempts) { }

    public record Decision(boolean allowed, String code, String message, int attempts) {
        static Decision allow() { return new Decision(true, "", "", 0); }
        static Decision allow(int attempts) { return new Decision(true, "", "", attempts); }
    }
}
