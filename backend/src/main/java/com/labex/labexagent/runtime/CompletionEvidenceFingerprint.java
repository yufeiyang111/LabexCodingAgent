package com.labex.labexagent.runtime;

import com.labex.labexagent.run.PreviewEvidence;
import com.labex.labexagent.run.RunCompletionEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/** 为同一组 durable 完成证据生成稳定身份，供不同收束策略共享。 */
final class CompletionEvidenceFingerprint {
    private CompletionEvidenceFingerprint() {
    }

    static String ofText(String text) {
        return sha256(normalize(text));
    }

    static String of(RunCompletionEvidence evidence) {
        if (evidence == null) {
            return sha256("completion-evidence:unavailable");
        }
        PreviewEvidence preview = evidence.preview();
        String canonical = "satisfied=" + evidence.satisfied()
                + "\nchanged=" + canonicalList(evidence.changedFiles())
                + "\nsuccess=" + canonicalList(evidence.successfulVerifications())
                + "\nfailed=" + canonicalList(evidence.failedVerifications())
                + "\nenvironment=" + canonicalList(evidence.environmentVerifications())
                + "\ntoolFailures=" + canonicalList(evidence.unresolvedToolFailures())
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
}
