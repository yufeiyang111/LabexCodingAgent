package com.labex.labexagent.runtime;

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

    public CompletionAssessment assess(Long taskId, Integer studentId, Integer projectId,
                                       boolean manualFileVerification) {
        RunCompletionEvidence evidence = evidenceService.evaluateAndPersist(
                taskId, studentId, projectId, manualFileVerification, "running");
        String guidance = evidence.satisfied() ? "" : buildGuidance(evidence);
        return new CompletionAssessment(evidence.satisfied(), evidence, guidance);
    }

    private String buildGuidance(RunCompletionEvidence evidence) {
        if (!evidence.failedVerifications().isEmpty()) {
            return "Completion evidence is not satisfied: fix the failed verification and run it again.";
        }
        if (!evidence.changedFiles().isEmpty() && evidence.successfulVerifications().isEmpty()) {
            return "Completion evidence is not satisfied: run a relevant verification after the latest file change.";
        }
        return "Completion evidence is not satisfied: resolve the recorded run risks before finalizing.";
    }

    public record CompletionAssessment(boolean allowed, RunCompletionEvidence evidence, String guidance) {
    }
}
