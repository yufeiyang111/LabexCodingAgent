package com.labex.labexagent.run;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 服务器可审计的完成证据；模型文本不能覆盖其中的失败事实。 */
public record RunCompletionEvidence(
        Long taskId,
        List<String> changedFiles,
        List<String> successfulVerifications,
        List<String> failedVerifications,
        List<String> environmentVerifications,
        List<String> unresolvedToolFailures,
        List<String> unresolvedRisks,
        List<Criterion> criteria,
        boolean satisfied,
        PreviewEvidence preview,
        LocalDateTime generatedAt) {

    private static final int MAX_ITEMS = 100;
    private static final int MAX_TEXT = 500;

    public RunCompletionEvidence {
        changedFiles = bounded(changedFiles);
        successfulVerifications = bounded(successfulVerifications);
        failedVerifications = bounded(failedVerifications);
        environmentVerifications = bounded(environmentVerifications);
        unresolvedToolFailures = bounded(unresolvedToolFailures);
        unresolvedRisks = bounded(unresolvedRisks);
        criteria = List.copyOf(criteria == null ? List.of() : criteria.stream().limit(MAX_ITEMS).toList());
        preview = preview == null ? new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", "") : preview;
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }

    /** 兼容尚未写入工具失败字段的旧调用方和历史 artifact。 */
    public RunCompletionEvidence(Long taskId, List<String> changedFiles, List<String> successfulVerifications,
                                 List<String> failedVerifications, List<String> environmentVerifications,
                                 List<String> unresolvedRisks, List<Criterion> criteria, boolean satisfied,
                                 PreviewEvidence preview, LocalDateTime generatedAt) {
        this(taskId, changedFiles, successfulVerifications, failedVerifications, environmentVerifications,
                List.of(), unresolvedRisks, criteria, satisfied, preview, generatedAt);
    }

    /** 兼容不携带 preview 的旧调用方。 */
    public RunCompletionEvidence(Long taskId, List<String> changedFiles, List<String> successfulVerifications,
                                 List<String> failedVerifications, List<String> environmentVerifications,
                                 List<String> unresolvedRisks, List<Criterion> criteria, boolean satisfied,
                                 LocalDateTime generatedAt) {
        this(taskId, changedFiles, successfulVerifications, failedVerifications, environmentVerifications,
                List.of(), unresolvedRisks, criteria, satisfied,
                new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", ""), generatedAt);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("changedFiles", changedFiles);
        payload.put("successfulVerifications", successfulVerifications);
        payload.put("failedVerifications", failedVerifications);
        payload.put("environmentVerifications", environmentVerifications);
        payload.put("unresolvedToolFailures", unresolvedToolFailures);
        payload.put("unresolvedRisks", unresolvedRisks);
        payload.put("criteria", criteria.stream().map(Criterion::toPayload).toList());
        payload.put("preview", Map.of(
                "status", preview.status().name().toLowerCase(java.util.Locale.ROOT),
                "publicUrl", preview.publicUrl(),
                "failureCode", preview.failureCode(),
                "outputPath", preview.outputPath()));
        payload.put("satisfied", satisfied);
        payload.put("generatedAt", generatedAt.toString());
        return payload;
    }

    private static List<String> bounded(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT) + "...")
                .distinct().limit(MAX_ITEMS).toList();
    }

    public record Criterion(String code, String label, boolean satisfied, String detail) {
        public Criterion {
            code = limit(code);
            label = limit(label);
            detail = limit(detail);
        }

        Map<String, Object> toPayload() {
            return Map.of("code", code, "label", label, "satisfied", satisfied, "detail", detail);
        }

        private static String limit(String value) {
            String safe = value == null ? "" : value;
            return safe.length() <= MAX_TEXT ? safe : safe.substring(0, MAX_TEXT) + "...";
        }
    }
}
