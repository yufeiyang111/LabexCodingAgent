package com.labex.labexagent.runtime;

import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.RunCompletionEvidence;
import com.labex.labexagent.run.RunCompletionEvidenceService;
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
        if (!evidence.failedVerifications().isEmpty()) {
            return "Completion evidence is not satisfied: fix the failed verification and run it again.";
        }
        if (!evidence.environmentVerifications().isEmpty()) {
            return "Completion evidence is not satisfied: the last verification could not run because of an "
                    + "environment failure (timeout, cancellation, DNS/network or infrastructure). "
                    + "Restore the environment or switch verification strategy, then run the verification again.";
        }
        if (!evidence.changedFiles().isEmpty() && evidence.successfulVerifications().isEmpty()) {
            return "Completion evidence is not satisfied: run a relevant verification after the latest file change.";
        }
        return "Completion evidence is not satisfied: resolve the recorded run risks before finalizing.";
    }

    public record CompletionAssessment(boolean allowed, RunCompletionEvidence evidence, String guidance) {
    }
}
