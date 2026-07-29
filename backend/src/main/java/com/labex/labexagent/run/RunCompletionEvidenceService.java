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
import java.util.List;
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
    private final RunCompletionPolicy policy = new RunCompletionPolicy();

    public RunCompletionEvidenceService(AgentFileChangeMapper fileChangeMapper,
                                        AgentVerificationMapper verificationMapper,
                                        AgentRunArtifactService artifactService) {
        this.fileChangeMapper = fileChangeMapper;
        this.verificationMapper = verificationMapper;
        this.artifactService = artifactService;
    }

    public RunCompletionEvidence evaluateAndPersist(Long taskId, Integer studentId, Integer projectId,
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
                .orderByAsc(AgentVerification::getCreateTime));
        List<String> changedFiles = changes.stream().map(AgentFileChange::getRelativePath)
                .filter(path -> path != null && !path.isBlank()).distinct().toList();
        List<String> passed = verifications.stream().filter(this::passed)
                .map(this::verificationLabel).toList();
        List<String> failed = new ArrayList<>(verifications.stream().filter(item -> !passed(item))
                .map(this::verificationLabel).toList());
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
        for (AgentRunArtifact artifact : artifactService.list(taskId, "tool_failure")) {
            String content = artifact.getContent() == null ? "" : artifact.getContent();
            failed.add("tool failure: " + boundedLabel(content));
            unresolvedRisks.add("unrecovered tool failure");
        }
        RunCompletionEvidence evidence = policy.evaluate(new RunCompletionPolicy.Input(
                taskId, changedFiles, passed, failed, manualFileVerification, runState, unresolvedRisks));
        RunCompletionEvidence existing = latest(taskId);
        if (sameEvidenceVersion(existing, evidence)) {
            return existing;
        }
        artifactService.recordDeterministic(taskId, "completion_evidence", "task-" + taskId,
                GSON.toJson(evidence.toPayload()));
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
                stringList(payload.getAsJsonArray("unresolvedRisks")),
                criteria(payload.getAsJsonArray("criteria")),
                payload.has("satisfied") && payload.get("satisfied").getAsBoolean(),
                parseGeneratedAt(payload.get("generatedAt")));
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
                && Objects.equals(left.unresolvedRisks(), right.unresolvedRisks())
                && Objects.equals(left.criteria(), right.criteria())
                && left.satisfied() == right.satisfied();
    }

    private boolean passed(AgentVerification verification) {
        String status = verification.getStatus() == null ? "" : verification.getStatus().toLowerCase(Locale.ROOT);
        return "passed".equals(status) || "manual_passed".equals(status);
    }

    private String boundedLabel(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "...";
    }

    private String verificationLabel(AgentVerification verification) {
        String command = verification.getCommand() == null || verification.getCommand().isBlank()
                ? "verification" : verification.getCommand().trim();
        command = SECRET.matcher(command).replaceAll("$1=[REDACTED]");
        if (command.length() > 240) command = command.substring(0, 240) + "...";
        return command + (verification.getExitCode() == null ? "" : " (exit " + verification.getExitCode() + ")");
    }
}
