package com.labex.labexagent.workspace;

import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.tool.FileContentFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 将 durable change-set、写前快照与写后验证投影成统一的 workspace 变更事实。
 *
 * <p>此投影只包含相对路径、内容摘要和字节数；绝不携带文件内容或宿主绝对路径。
 * 写前状态必须来自实际文件系统或 durable snapshot，写后状态必须来自实际 postcondition
 * verification，不能由模型参数或命令文本推断。</p>
 */
public final class WorkspaceMutationEvidence {
    private WorkspaceMutationEvidence() {
    }

    /**
     * 基于已落库的变更和真实写后验证构造公开可投影的事实。调用方负责提供可信的写前状态：
     * 直接文件写入传入持有 workspace lease 时读取到的物理状态；快照路径传入 durable snapshot 状态。
     */
    public static Map<String, Object> fromChanges(List<PendingChange> changes,
                                                   Map<String, ? extends Map<String, Object>> beforeTargetStates,
                                                   Map<String, Object> workspaceVerification) {
        Map<String, Map<String, Object>> afterTargetStates = observedTargetStates(workspaceVerification);
        LinkedHashSet<String> changeIds = new LinkedHashSet<>();
        List<Map<String, Object>> targets = new ArrayList<>();
        for (PendingChange change : safeChanges(changes)) {
            String path = normalizeRelativePath(change.getRelativePath());
            if (path.isBlank()) {
                continue;
            }
            String changeId = change.getId() == null ? "" : change.getId().trim();
            if (!changeId.isBlank()) {
                changeIds.add(changeId);
            }
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("path", path);
            target.put("operation", normalizedOperation(change.getChangeType()));
            if (!changeId.isBlank()) {
                target.put("changeId", changeId);
            }
            target.put("before", copyState(beforeTargetStates == null ? null : beforeTargetStates.get(path)));
            target.put("after", afterTargetStates.getOrDefault(path,
                    Map.of("state", "unobserved", "verified", false)));
            targets.add(Map.copyOf(target));
        }
        if (targets.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("state", "applied");
        evidence.put("changeIds", List.copyOf(changeIds));
        evidence.put("targets", List.copyOf(targets));
        return Map.copyOf(evidence);
    }

    /**
     * 从 durable snapshot 的 {@link PendingChange} 生成写前状态。rename 的旧路径不在
     * PendingChange 中单独保存，不能把旧文件内容错误归属到新目标，因此明确标记 unavailable。
     */
    public static Map<String, Map<String, Object>> snapshotBeforeStates(List<PendingChange> changes) {
        Map<String, Map<String, Object>> states = new LinkedHashMap<>();
        for (PendingChange change : safeChanges(changes)) {
            String path = normalizeRelativePath(change.getRelativePath());
            if (path.isBlank()) {
                continue;
            }
            String operation = normalizedOperation(change.getChangeType());
            Map<String, Object> state;
            if ("create".equals(operation)) {
                state = Map.of("state", "absent");
            } else if ("modify".equals(operation) || "delete".equals(operation)) {
                String content = change.getBeforeContent() == null ? "" : change.getBeforeContent();
                state = Map.of(
                        "state", "present",
                        "sha256", FileContentFingerprint.sha256(content),
                        "bytes", (long) content.getBytes(StandardCharsets.UTF_8).length);
            } else {
                state = Map.of("state", "unavailable");
            }
            states.put(path, state);
        }
        return Map.copyOf(states);
    }

    /**
     * 以命令完成后的真实文件系统状态复核 snapshot 中记录到的 target。
     * 这一步不从命令文本推断文件影响：只有 change-set 中实际出现的相对路径才会被检查。
     */
    public static Map<String, Object> verifyPostconditions(StudentProject project, List<PendingChange> changes) {
        List<PendingChange> safeChanges = safeChanges(changes);
        if (safeChanges.isEmpty()) {
            return Map.of("state", "no_change", "targets", List.of());
        }
        try {
            SecureWorkspacePath workspace = ProjectWorkspace.paths(project);
            List<Map<String, Object>> targets = new ArrayList<>();
            boolean verified = true;
            boolean unavailable = false;
            for (PendingChange change : safeChanges) {
                String path = normalizeRelativePath(change.getRelativePath());
                if (path.isBlank()) {
                    unavailable = true;
                    continue;
                }
                String operation = normalizedOperation(change.getChangeType());
                String expectedState = expectedAfterState(operation);
                Map<String, Object> targetEvidence = new LinkedHashMap<>();
                targetEvidence.put("path", path);
                targetEvidence.put("expectedState", expectedState);
                if ("unavailable".equals(expectedState)) {
                    targetEvidence.put("observedState", "unobserved");
                    targets.add(Map.copyOf(targetEvidence));
                    unavailable = true;
                    continue;
                }
                Path target = workspace.resolveForCreate(path);
                String observedState;
                long observedBytes = -1L;
                String observedSha256 = "";
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    observedState = "absent";
                } else if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    observedState = "present";
                    observedBytes = Files.size(target);
                    observedSha256 = FileContentFingerprint.sha256(target);
                } else {
                    observedState = "not_regular";
                }
                boolean targetVerified = expectedState.equals(observedState);
                if ("present".equals(expectedState)) {
                    String expectedContent = change.getAfterContent() == null ? "" : change.getAfterContent();
                    long expectedBytes = expectedContent.getBytes(StandardCharsets.UTF_8).length;
                    String expectedSha256 = FileContentFingerprint.sha256(expectedContent);
                    targetEvidence.put("expectedSha256", expectedSha256);
                    targetEvidence.put("expectedBytes", expectedBytes);
                    targetVerified = targetVerified && expectedBytes == observedBytes
                            && expectedSha256.equals(observedSha256);
                }
                targetEvidence.put("observedState", observedState);
                if (!observedSha256.isBlank()) {
                    targetEvidence.put("observedSha256", observedSha256);
                    targetEvidence.put("observedBytes", observedBytes);
                }
                targets.add(Map.copyOf(targetEvidence));
                verified = verified && targetVerified;
            }
            if (targets.isEmpty()) {
                return Map.of("state", "unavailable", "targets", List.of());
            }
            return Map.of("state", unavailable ? "unavailable" : (verified ? "verified" : "mismatch"),
                    "targets", List.copyOf(targets));
        } catch (IOException | RuntimeException exception) {
            // 无法读取实际 target 时不把 snapshot 推断为验证成功。
            return Map.of("state", "unavailable", "targets", List.of());
        }
    }

    private static String expectedAfterState(String operation) {
        return switch (operation) {
            case "delete" -> "absent";
            case "create", "modify", "rename" -> "present";
            default -> "unavailable";
        };
    }
    private static List<PendingChange> safeChanges(List<PendingChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        return changes.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static Map<String, Object> copyState(Map<String, Object> state) {
        return state == null || state.isEmpty() ? Map.of("state", "unavailable") : Map.copyOf(state);
    }

    private static Map<String, Map<String, Object>> observedTargetStates(Map<String, Object> workspaceVerification) {
        if (workspaceVerification == null) {
            return Map.of();
        }
        Object rawTargets = workspaceVerification.get("targets");
        if (!(rawTargets instanceof List<?> targets)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object rawTarget : targets) {
            if (!(rawTarget instanceof Map<?, ?> source)) {
                continue;
            }
            String path = normalizeRelativePath(stringValue(source.get("path")));
            String expectedState = normalizeObservedState(stringValue(source.get("expectedState")));
            String observedState = normalizeObservedState(stringValue(source.get("observedState")));
            if (path.isBlank() || observedState.isBlank()) {
                continue;
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("state", observedState);
            copyIfPresent(source, after, "observedSha256", "sha256");
            copyIfPresent(source, after, "observedBytes", "bytes");
            after.put("verified", verifiedTarget(source, expectedState, observedState));
            result.put(path, Map.copyOf(after));
        }
        return Map.copyOf(result);
    }

    private static boolean verifiedTarget(Map<?, ?> source, String expectedState, String observedState) {
        if (expectedState.isBlank() || !expectedState.equals(observedState)) {
            return false;
        }
        if (!"present".equals(expectedState)) {
            return true;
        }
        return sameValue(source.get("expectedSha256"), source.get("observedSha256"))
                && sameValue(source.get("expectedBytes"), source.get("observedBytes"));
    }

    private static boolean sameValue(Object expected, Object observed) {
        return expected != null && observed != null && String.valueOf(expected).equals(String.valueOf(observed));
    }
    private static void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String sourceKey,
                                      String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null && !(value instanceof String text && text.isBlank())) {
            target.put(targetKey, value);
        }
    }

    private static String normalizedOperation(String changeType) {
        return changeType == null || changeType.isBlank()
                ? "modify" : changeType.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeObservedState(String value) {
        String normalized = stringValue(value).toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "regular_file", "present" -> "present";
            case "not_regular_file", "not_regular" -> "not_regular";
            case "absent" -> "absent";
            case "" -> "";
            default -> normalized.matches("[a-z0-9_]{1,64}") ? normalized : "unobserved";
        };
    }

    /**
     * 规范化可公开投影的 workspace 相对路径；空值、绝对路径、越界路径和无效路径返回空字符串。
     * durable Part 重放也必须复用该边界，避免把历史脏数据带回模型进度提示。
     */
    public static String normalizeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String candidate = value.trim().replace('\\', '/');
        while (candidate.startsWith("./")) {
            candidate = candidate.substring(2);
        }
        try {
            Path path = Path.of(candidate).normalize();
            if (path.isAbsolute() || candidate.startsWith("/") || candidate.matches("^[A-Za-z]:.*")) {
                return "";
            }
            String normalized = path.toString().replace('\\', '/');
            return normalized.equals(".") || normalized.equals("..") || normalized.startsWith("../")
                    ? "" : normalized;
        } catch (InvalidPathException invalid) {
            return "";
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
