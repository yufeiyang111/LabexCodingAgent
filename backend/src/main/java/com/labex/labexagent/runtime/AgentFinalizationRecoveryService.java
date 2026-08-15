package com.labex.labexagent.runtime;

import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.run.PreviewEvidence;
import com.labex.labexagent.run.RunCompletionEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 以当前 epoch 的 durable finalization Part 为依据，限制同一完成证据的自动恢复次数。
 * 该服务不维护内存计数，JVM 重启后仍可从 Part 投影恢复同一决策。
 */
@Service
public final class AgentFinalizationRecoveryService {
    private final AgentRunPartService partService;
    private final AgentLoopProperties loopProperties;

    public AgentFinalizationRecoveryService(AgentRunPartService partService, AgentLoopProperties loopProperties) {
        this.partService = partService;
        this.loopProperties = loopProperties;
    }

    public Decision decide(Long taskId, long executionEpoch, RunCompletionEvidence evidence, String finalText) {
        String evidenceFingerprint = evidenceFingerprint(evidence);
        String finalClaimFingerprint = sha256(normalize(finalText));
        int recoveryLimit = loopProperties == null ? 1 : loopProperties.getFinalizationRecoveryLimit();
        int previousAttempts = partService == null ? 0
                : partService.currentEpochFinalizationRecoveryAttempts(taskId, executionEpoch, evidenceFingerprint);
        boolean recoveryAllowed = previousAttempts < recoveryLimit;
        int recoveryAttempt = recoveryAllowed ? previousAttempts + 1 : previousAttempts;
        return new Decision(recoveryAllowed, previousAttempts, recoveryAttempt, recoveryLimit,
                evidenceFingerprint, finalClaimFingerprint);
    }

    static String evidenceFingerprint(RunCompletionEvidence evidence) {
        if (evidence == null) {
            return sha256("completion-evidence:unavailable");
        }
        PreviewEvidence preview = evidence.preview();
        String canonical = "satisfied=" + evidence.satisfied()
                + "\nchanged=" + canonicalList(evidence.changedFiles())
                + "\nsuccess=" + canonicalList(evidence.successfulVerifications())
                + "\nfailed=" + canonicalList(evidence.failedVerifications())
                + "\nenvironment=" + canonicalList(evidence.environmentVerifications())
                + "\nrisks=" + canonicalList(evidence.unresolvedRisks())
                + "\ncriteria=" + canonicalCriteria(evidence.criteria())
                + "\npreviewStatus=" + (preview == null ? "" : preview.status())
                + "\npreviewUrl=" + (preview == null ? "" : preview.publicUrl())
                + "\npreviewFailure=" + (preview == null ? "" : preview.failureCode());
        return sha256(canonical);
    }

    private static String canonicalList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream().filter(value -> value != null).sorted().reduce((left, right) -> left + "\u001f" + right)
                .orElse("");
    }

    private static String canonicalCriteria(List<RunCompletionEvidence.Criterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return "";
        }
        return criteria.stream()
                .filter(criterion -> criterion != null)
                .sorted(Comparator.comparing(RunCompletionEvidence.Criterion::code)
                        .thenComparing(RunCompletionEvidence.Criterion::label))
                .map(criterion -> criterion.code() + "\u001f" + criterion.satisfied() + "\u001f" + criterion.detail())
                .reduce((left, right) -> left + "\u001e" + right)
                .orElse("");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(String.format("%02x", current));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record Decision(boolean recoveryAllowed, int previousAttempts, int recoveryAttempt, int recoveryLimit,
                           String evidenceFingerprint, String finalClaimFingerprint) {
    }
}
