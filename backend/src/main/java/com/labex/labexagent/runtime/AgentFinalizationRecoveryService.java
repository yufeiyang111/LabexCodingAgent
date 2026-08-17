package com.labex.labexagent.runtime;

import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.run.RunCompletionEvidence;
import org.springframework.stereotype.Service;

/**
 * 以当前 epoch 的 durable finalization Part 为依据，限制同一完成证据的自动恢复次数。
 * 该服务不维护内存计数，JVM 重启后仍可从 Part 投影恢复同一决策。
 */
@Service
public class AgentFinalizationRecoveryService {
    private final AgentRunPartService partService;
    private final AgentLoopProperties loopProperties;

    public AgentFinalizationRecoveryService(AgentRunPartService partService, AgentLoopProperties loopProperties) {
        this.partService = partService;
        this.loopProperties = loopProperties;
    }

    public Decision decide(Long taskId, long executionEpoch, RunCompletionEvidence evidence, String finalText) {
        String evidenceFingerprint = evidenceFingerprint(evidence);
        String finalClaimFingerprint = CompletionEvidenceFingerprint.ofText(finalText);
        int recoveryLimit = loopProperties == null ? 1 : loopProperties.getFinalizationRecoveryLimit();
        int previousAttempts = partService == null ? 0
                : partService.currentEpochFinalizationRecoveryAttempts(taskId, executionEpoch, evidenceFingerprint);
        boolean recoveryAllowed = previousAttempts < recoveryLimit;
        int recoveryAttempt = recoveryAllowed ? previousAttempts + 1 : previousAttempts;
        return new Decision(recoveryAllowed, previousAttempts, recoveryAttempt, recoveryLimit,
                evidenceFingerprint, finalClaimFingerprint);
    }

    static String evidenceFingerprint(RunCompletionEvidence evidence) {
        return CompletionEvidenceFingerprint.of(evidence);
    }

    public record Decision(boolean recoveryAllowed, int previousAttempts, int recoveryAttempt, int recoveryLimit,
                           String evidenceFingerprint, String finalClaimFingerprint) {
    }
}
