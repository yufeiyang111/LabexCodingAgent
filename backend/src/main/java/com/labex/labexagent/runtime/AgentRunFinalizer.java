package com.labex.labexagent.runtime;

import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.RunCompletionEvidence;
import com.labex.labexagent.run.RunCompletionEvidenceService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 将模型的 final 文本转换为服务器证据判定，模型本身不能设置完成布尔值。 */
@Service
public final class AgentRunFinalizer {
    private final RunCompletionEvidenceService evidenceService;

    public AgentRunFinalizer(RunCompletionEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    /**
     * @deprecated 仅兼容既有调用与测试；执行器路径必须使用
     * {@link #assess(ExecutionFence, Long, Integer, Integer, boolean)}，本 overload 计划在后续版本移除。
     */
    @Deprecated
    public CompletionAssessment assess(Long taskId, Integer studentId, Integer projectId,
                                       boolean manualFileVerification) {
        RunCompletionEvidence evidence = evidenceService.evaluateAndPersist(
                taskId, studentId, projectId, manualFileVerification, "running");
        return assessEvidence(evidence);
    }

    /** 执行者携带其 lease 派生 fence 判定完成证据；stale fence 由写入方抛出 typed failure。 */
    public CompletionAssessment assess(ExecutionFence fence, Long taskId, Integer studentId, Integer projectId,
                                       boolean manualFileVerification) {
        RunCompletionEvidence evidence = evidenceService.evaluateAndPersist(
                fence, taskId, studentId, projectId, manualFileVerification, "running");
        return assessEvidence(evidence);
    }

    private CompletionAssessment assessEvidence(RunCompletionEvidence evidence) {
        String guidance = evidence.satisfied() ? "" : buildGuidance(evidence);
        return new CompletionAssessment(evidence.satisfied(), evidence, guidance);
    }

    private String buildGuidance(RunCompletionEvidence evidence) {
        List<String> blockers = new ArrayList<>();
        for (String verification : evidence.failedVerifications()) {
            blockers.add("failed verification: " + verification);
        }
        for (String verification : evidence.environmentVerifications()) {
            blockers.add("environment-blocked verification: " + verification);
        }
        for (String risk : evidence.unresolvedRisks()) {
            blockers.add("recorded risk: " + risk);
        }
        if (!evidence.changedFiles().isEmpty() && evidence.successfulVerifications().isEmpty()) {
            blockers.add("changed files have no successful server-recorded verification");
        }
        if (blockers.isEmpty()) {
            blockers.add("the server completion policy is not satisfied");
        }
        return "Completion evidence is not satisfied. Resolve only these recorded blockers before finalizing:\n- "
                + String.join("\n- ", blockers)
                + "\nUse a server-recognized verification path or verification strategy after the latest relevant change. "
                + "Do not add configuration or tests unless they change one of the blockers above.";
    }

    public record CompletionAssessment(boolean allowed, RunCompletionEvidence evidence, String guidance) {
    }
}
