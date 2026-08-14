package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentFileChange;
import com.labex.entity.AgentRunArtifact;
import com.labex.entity.AgentVerification;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentVerificationMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RunCompletionEvidenceService {
    private static final Gson GSON = new Gson();
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|token|password|secret|authorization)\s*[:=]\s*([^\s]+)");
    private final AgentFileChangeMapper fileChangeMapper;
    private final AgentVerificationMapper verificationMapper;
    private final AgentRunArtifactService artifactService;
    private final AgentRunExecutionLeaseService leaseService;
    private final RunCompletionPolicy policy = new RunCompletionPolicy();

    @org.springframework.beans.factory.annotation.Autowired
    public RunCompletionEvidenceService(AgentFileChangeMapper fileChangeMapper,
                                        AgentVerificationMapper verificationMapper,
                                        AgentRunArtifactService artifactService,
                                        AgentRunExecutionLeaseService leaseService) {
        this.fileChangeMapper = fileChangeMapper;
        this.verificationMapper = verificationMapper;
        this.artifactService = artifactService;
        this.leaseService = leaseService;
    }

    public RunCompletionEvidenceService(AgentFileChangeMapper fileChangeMapper,
                                        AgentVerificationMapper verificationMapper,
                                        AgentRunArtifactService artifactService) {
        this(fileChangeMapper, verificationMapper, artifactService, null);
    }

    public RunCompletionEvidence evaluateAndPersist(Long taskId, Integer studentId, Integer projectId,
                                                     boolean manualFileVerification, String runState) {
        return evaluateAndPersistInternal(null, taskId, studentId, projectId, manualFileVerification, runState);
    }

    /**
     * Executor-fenced completion evidence 写入：先验证 {@link ExecutionFence}（owner + 精确 epoch +
     * 未过期 lease），stale fence 抛出 typed failure；completion artifact 走 fenced 写入，不产生部分持久化。
     */
    public RunCompletionEvidence evaluateAndPersist(ExecutionFence fence, Long taskId, Integer studentId,
                                                     Integer projectId, boolean manualFileVerification,
                                                     String runState) {
        requireFence(fence);
        return evaluateAndPersistInternal(fence, taskId, studentId, projectId, manualFileVerification, runState);
    }

    private RunCompletionEvidence evaluateAndPersistInternal(ExecutionFence fence, Long taskId,
                                                             Integer studentId, Integer projectId,
                                                             boolean manualFileVerification, String runState) {
        List<AgentFileChange> changes = fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>()
                .eq(AgentFileChange::getTaskId, taskId)
                .eq(AgentFileChange::getStudentId, studentId)
                .eq(AgentFileChange::getProjectId, projectId)
                .notIn(AgentFileChange::getStatus, List.of("rejected", "undone"))
                .orderByAsc(AgentFileChange::getCreateTime));
        List<AgentVerification> verifications = verificationMapper.selectList(new LambdaQueryWrapper<AgentVerification>()
                .eq(AgentVerification::getTaskId, taskId)
                .eq(AgentVerification::getStudentId, studentId)
                .eq(AgentVerification::getProjectId, projectId)
                .orderByAsc(AgentVerification::getCreateTime)
                .orderByAsc(AgentVerification::getVerificationId));
        List<AgentVerification> effectiveVerifications = latestVerificationPerCommand(verifications);
        List<String> changedFiles = changes.stream().map(AgentFileChange::getRelativePath)
                .filter(path -> path != null && !path.isBlank()).distinct().toList();
        List<String> passed = effectiveVerifications.stream().filter(this::passed)
                .map(this::verificationLabel).toList();
        List<String> failed = new ArrayList<>(effectiveVerifications.stream()
                .filter(item -> !passed(item) && !environmentBlocked(item))
                .map(this::verificationLabel).toList());
        // 环境受阻（超时/取消/基础设施）不是代码验证失败：单独记录，给模型可行动的环境恢复指引。
        List<String> environmentVerifications = effectiveVerifications.stream()
                .filter(item -> !passed(item) && environmentBlocked(item))
                .map(this::verificationLabel).toList();
        List<String> unresolvedRisks = new ArrayList<>();
        boolean hasSuccessfulVerification = !passed.isEmpty();
        for (AgentRunArtifact artifact : artifactService.list(taskId, "post_edit_verification")) {
            String content = artifact.getContent() == null ? "" : artifact.getContent();
            if (content.contains("status=UNAVAILABLE")) {
                // LSP 是辅助诊断；已有服务器验证通过时，不应因本机未安装语言服务器而无限阻塞完成。
                if (!hasSuccessfulVerification) {
                    unresolvedRisks.add("post-edit verification unavailable");
                }
            } else if (content.contains("status=FAIL")) {
                unresolvedRisks.add("post-edit diagnostics failed");
            }
        }
        RunCompletionEvidence evidence = policy.evaluate(new RunCompletionPolicy.Input(
                taskId, changedFiles, passed, failed, environmentVerifications, manualFileVerification, runState,
                unresolvedRisks));
        RunCompletionEvidence existing = latest(taskId);
        if (sameEvidenceVersion(existing, evidence)) {
            return existing;
        }
        if (fence == null) {
            artifactService.recordDeterministic(taskId, "completion_evidence", "task-" + taskId,
                    GSON.toJson(evidence.toPayload()));
        } else {
            artifactService.recordDeterministic(fence, taskId, "completion_evidence", "task-" + taskId,
                    GSON.toJson(evidence.toPayload()));
        }
        return evidence;
    }

    public RunCompletionEvidence latest(Long taskId) {
        AgentRunArtifact artifact = artifactService.latest(taskId, "completion_evidence");
        if (artifact == null || artifact.getContent() == null || artifact.getContent().isBlank()) {
            return null;
        }
        JsonObject payload = JsonParser.parseString(artifact.getContent()).getAsJsonObject();
        return new RunCompletionEvidence(
                nullableLong(payload.get("taskId")),
                stringList(payload.getAsJsonArray("changedFiles")),
                stringList(payload.getAsJsonArray("successfulVerifications")),
                stringList(payload.getAsJsonArray("failedVerifications")),
                optionalStringList(payload.get("environmentVerifications")),
                stringList(payload.getAsJsonArray("unresolvedRisks")),
                criteria(payload.getAsJsonArray("criteria")),
                payload.has("satisfied") && payload.get("satisfied").getAsBoolean(),
                parseGeneratedAt(payload.get("generatedAt")));
    }

    private List<String> optionalStringList(JsonElement value) {
        if (value == null || !value.isJsonArray()) return List.of();
        return stringList(value.getAsJsonArray());
    }

    private Long nullableLong(JsonElement value) {
        return value == null || value.isJsonNull() ? null : value.getAsLong();
    }

    private List<String> stringList(JsonArray values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value != null && !value.isJsonNull()) result.add(value.getAsString());
        }
        return result;
    }

    private List<RunCompletionEvidence.Criterion> criteria(JsonArray values) {
        if (values == null) return List.of();
        List<RunCompletionEvidence.Criterion> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value == null || !value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            result.add(new RunCompletionEvidence.Criterion(
                    stringValue(item.get("code")),
                    stringValue(item.get("label")),
                    item.has("satisfied") && item.get("satisfied").getAsBoolean(),
                    stringValue(item.get("detail"))));
        }
        return result;
    }

    private String stringValue(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private LocalDateTime parseGeneratedAt(JsonElement value) {
        return value == null || value.isJsonNull() ? null : LocalDateTime.parse(value.getAsString());
    }

    private boolean sameEvidenceVersion(RunCompletionEvidence left, RunCompletionEvidence right) {
        return left != null && right != null
                && Objects.equals(left.taskId(), right.taskId())
                && Objects.equals(left.changedFiles(), right.changedFiles())
                && Objects.equals(left.successfulVerifications(), right.successfulVerifications())
                && Objects.equals(left.failedVerifications(), right.failedVerifications())
                && Objects.equals(left.environmentVerifications(), right.environmentVerifications())
                && Objects.equals(left.unresolvedRisks(), right.unresolvedRisks())
                && Objects.equals(left.criteria(), right.criteria())
                && left.satisfied() == right.satisfied();
    }

    /**
     * 同一规范化命令只保留最后一条验证记录，使恢复后的成功能够覆盖历史失败。
     * 不同命令仍分别保留，避免用无关验证掩盖失败。
     */
    private List<AgentVerification> latestVerificationPerCommand(List<AgentVerification> verifications) {
        Map<String, AgentVerification> latest = new LinkedHashMap<>();
        int anonymous = 0;
        for (AgentVerification verification : verifications == null ? List.<AgentVerification>of() : verifications) {
            if (verification == null) continue;
            String key = normalizedVerificationCommand(verification.getCommand());
            if (key.isBlank()) {
                key = "verification:" + (verification.getVerificationId() == null
                        ? "anonymous-" + anonymous++ : verification.getVerificationId());
            }
            // remove + put 保持最后一次出现的命令顺序。
            latest.remove(key);
            latest.put(key, verification);
        }
        return List.copyOf(latest.values());
    }

    private String normalizedVerificationCommand(String command) {
        return displayVerificationCommand(command)
                .replaceFirst("(?i)^python(?:\\d+(?:\\.\\d+)*)?\\b", "python");
    }

    private String displayVerificationCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }

    private boolean passed(AgentVerification verification) {
        String status = verification.getStatus() == null ? "" : verification.getStatus().toLowerCase(Locale.ROOT);
        return "passed".equals(status) || "manual_passed".equals(status);
    }

    /** 超时/取消/基础设施受阻的验证不是代码失败：完成证据必须与真实测试失败分开记录。 */
    private boolean environmentBlocked(AgentVerification verification) {
        String status = verification.getStatus() == null ? "" : verification.getStatus().toLowerCase(Locale.ROOT);
        return "timed_out".equals(status) || "cancelled".equals(status) || "infrastructure_error".equals(status);
    }

    private String boundedLabel(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    private String verificationLabel(AgentVerification verification) {
        String command = displayVerificationCommand(verification.getCommand());
        if (command.isBlank()) command = "verification";
        command = SECRET.matcher(command).replaceAll("$1=[REDACTED]");
        if (command.length() > 240) command = command.substring(0, 240) + "...";
        if (verification.getExitCode() != null) {
            return command + " (exit " + verification.getExitCode() + ")";
        }
        String status = verification.getStatus() == null ? "" : verification.getStatus().toLowerCase(Locale.ROOT);
        if (environmentBlocked(verification)) {
            return command + " (status=" + status + ")";
        }
        return command;
    }

    private void requireFence(ExecutionFence fence) {
        if (leaseService == null) {
            throw new IllegalStateException("ExecutionFence support is not configured");
        }
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for executor-originated writes");
        }
        leaseService.requireActiveFence(fence, LocalDateTime.now());
    }
}
