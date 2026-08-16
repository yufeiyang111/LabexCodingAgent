package com.labex.labexagent.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.tool.ToolResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 进度无关的主循环保险丝和工具调用循环检测器。
 *
 * <p>总迭代上限默认关闭；死循环以完整工具输入的重复模式识别。第一次命中要求模型自动换策略，
 * 同一模式再次命中时升级为用户决策，避免依靠任意总轮数终止。</p>
 */
public final class AgentLoopGuard {
    private final AgentLoopProperties properties;
    private final Deque<String> recentToolSignatures = new ArrayDeque<>();
    private final Set<String> challengedPatterns = new HashSet<>();
    private final Map<String, Integer> failedToolSignatures = new HashMap<>();
    private int automaticStrategySwitches;
    private int nonProgressIterations;
    private String progressFingerprint;

    public AgentLoopGuard(AgentLoopProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("AgentLoopProperties is required");
        }
        this.properties = properties;
    }

    public IterationDecision beforeIteration(int iteration) {
        int hardMax = properties.getHardMaxIterations();
        if (hardMax > 0 && iteration > hardMax) {
            return new IterationDecision(
                    IterationAction.STOP,
                    "hard_limit",
                    hardMax,
                    nonProgressIterations);
        }
        if (nonProgressIterations >= properties.getMaxNonProgressIterations()) {
            return new IterationDecision(
                    IterationAction.STOP,
                    "non_progress",
                    hardMax,
                    nonProgressIterations);
        }
        return new IterationDecision(IterationAction.CONTINUE, "", hardMax, nonProgressIterations);
    }

    public void recordModelNoProgress() {
        nonProgressIterations = Math.min(100_000, nonProgressIterations + 1);
    }

    public void restoreNonProgressIterations(int durableCount) {
        nonProgressIterations = Math.max(0, Math.min(100_000, durableCount));
    }

    public int nonProgressIterations() {
        return nonProgressIterations;
    }

    public void recordToolResult(boolean success) {
        recordToolResult("", success);
    }

    /**
     * 仅将真实成功视为可重置的执行进展；不同输入的失败由签名级重试检测处理，
     * 不能被误计为模型连续无进展，否则正常的诊断/探索会在固定轮数后被提前停止。
     */
    public void recordToolResult(String signature, ToolResult result) {
        recordToolResult(signature, result != null && result.isSuccessfulExecutionOutcome());
    }

    public void recordToolResult(String signature, boolean success) {
        if (success) {
            nonProgressIterations = 0;
            if (signature != null && !signature.isBlank()) {
                failedToolSignatures.remove(signature);
            }
            return;
        }
        if (signature != null && !signature.isBlank()) {
            failedToolSignatures.merge(signature, 1, Integer::sum);
        }
    }

    public void restoreDurableToolCall(String toolName, JsonObject arguments) {
        recentToolSignatures.addLast(canonicalSignature(toolName, arguments));
        trimHistory();
    }

    public ToolDecision beforeToolCall(String toolName, JsonObject arguments) {
        return beforeToolCall(toolName, arguments, "");
    }

    public ToolDecision beforeToolCall(String toolName, JsonObject arguments, String durableProgressFingerprint) {
        resetAttemptHistoryWhenProgressChanges(durableProgressFingerprint);
        String signature = canonicalSignature(toolName, arguments);
        recentToolSignatures.addLast(signature);
        trimHistory();

        int failedAttempts = failedToolSignatures.getOrDefault(signature, 0);
        if (failedAttempts >= Math.max(1, properties.getRepeatedToolCallThreshold() - 1)) {
            return repeatedFailureDecision(signature, failedAttempts);
        }

        LoopPattern loop = findRepeatedSuffix();
        if (loop == null) {
            return new ToolDecision(ToolAction.ALLOW, signature, "", 0, "");
        }

        boolean firstChallenge = challengedPatterns.add(loop.key());
        if (firstChallenge && automaticStrategySwitches < properties.getMaxAutomaticStrategySwitches()) {
            automaticStrategySwitches++;
            return new ToolDecision(
                    ToolAction.SWITCH_STRATEGY,
                    signature,
                    "Detected a repeated tool-call pattern; the current call was blocked so the agent can change strategy.",
                    loop.cycleLength(),
                    loop.key());
        }
        return new ToolDecision(
                ToolAction.REQUEST_USER,
                signature,
                "The agent repeated a tool-call pattern after being told to change strategy.",
                loop.cycleLength(),
                loop.key());
    }

    private ToolDecision repeatedFailureDecision(String signature, int failedAttempts) {
        String patternKey = "failed:" + signature;
        boolean firstChallenge = challengedPatterns.add(patternKey);
        if (firstChallenge && automaticStrategySwitches < properties.getMaxAutomaticStrategySwitches()) {
            automaticStrategySwitches++;
            return new ToolDecision(
                    ToolAction.SWITCH_STRATEGY,
                    signature,
                    "Equivalent tool calls already failed " + failedAttempts
                            + " times; inspect or modify the workspace before retrying.",
                    1,
                    patternKey);
        }
        return new ToolDecision(
                ToolAction.REQUEST_USER,
                signature,
                "The agent retried an equivalent failed tool call after a required strategy change.",
                1,
                patternKey);
    }

    private void resetAttemptHistoryWhenProgressChanges(String durableProgressFingerprint) {
        String next = durableProgressFingerprint == null ? "" : durableProgressFingerprint.strip();
        if (next.isBlank()) {
            return;
        }
        if (progressFingerprint != null && !progressFingerprint.equals(next)) {
            recentToolSignatures.clear();
            challengedPatterns.clear();
            failedToolSignatures.clear();
            automaticStrategySwitches = 0;
            nonProgressIterations = 0;
        }
        progressFingerprint = next;
    }

    private void trimHistory() {
        int maxEntries = properties.getRepeatedToolCallThreshold() * properties.getMaxCycleLength();
        while (recentToolSignatures.size() > maxEntries) {
            recentToolSignatures.removeFirst();
        }
    }

    private LoopPattern findRepeatedSuffix() {
        List<String> history = new ArrayList<>(recentToolSignatures);
        int repeats = properties.getRepeatedToolCallThreshold();
        for (int cycleLength = 1; cycleLength <= properties.getMaxCycleLength(); cycleLength++) {
            int required = cycleLength * repeats;
            if (history.size() < required) {
                continue;
            }
            int start = history.size() - required;
            boolean repeated = true;
            for (int offset = cycleLength; offset < required; offset++) {
                if (!history.get(start + offset).equals(history.get(start + (offset % cycleLength)))) {
                    repeated = false;
                    break;
                }
            }
            if (repeated) {
                List<String> cycle = history.subList(history.size() - cycleLength, history.size());
                return new LoopPattern(cycleLength, String.join("\u001f", cycle));
            }
        }
        return null;
    }

    static String canonicalSignature(String toolName, JsonObject arguments) {
        String normalizedTool = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        String canonical = normalizedTool + ":" + canonicalJson(arguments == null ? new JsonObject() : arguments);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(normalizedTool.length() + 65);
            result.append(normalizedTool).append(':');
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonicalJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            return element.toString();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) result.append(',');
                result.append(canonicalJson(array.get(i)));
            }
            return result.append(']').toString();
        }
        JsonObject object = element.getAsJsonObject();
        List<String> keys = object.keySet().stream().sorted(Comparator.naturalOrder()).toList();
        StringBuilder result = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) result.append(',');
            String key = keys.get(i);
            result.append(new com.google.gson.JsonPrimitive(key)).append(':').append(canonicalJson(object.get(key)));
        }
        return result.append('}').toString();
    }

    public enum IterationAction {
        CONTINUE,
        STOP
    }

    public enum ToolAction {
        ALLOW,
        SWITCH_STRATEGY,
        REQUEST_USER
    }

    public record IterationDecision(IterationAction action, String reason, int configuredHardMax, int nonProgressIterations) {
    }

    public record ToolDecision(
            ToolAction action,
            String signature,
            String reason,
            int cycleLength,
            String patternKey) {
    }

    private record LoopPattern(int cycleLength, String key) {
    }
}