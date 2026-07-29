package com.labex.labexagent.run;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RunCompletionEvidence(
        Long taskId,
        List<String> changedFiles,
        List<String> successfulVerifications,
        List<String> failedVerifications,
        List<String> unresolvedRisks,
        List<Criterion> criteria,
        boolean satisfied,
        LocalDateTime generatedAt) {

    private static final int MAX_ITEMS = 100;
    private static final int MAX_TEXT = 500;

    public RunCompletionEvidence {
        changedFiles = bounded(changedFiles);
        successfulVerifications = bounded(successfulVerifications);
        failedVerifications = bounded(failedVerifications);
        unresolvedRisks = bounded(unresolvedRisks);
        criteria = List.copyOf(criteria == null ? List.of() : criteria.stream().limit(MAX_ITEMS).toList());
        generatedAt = generatedAt == null ? LocalDateTime.now() : generatedAt;
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("changedFiles", changedFiles);
        payload.put("successfulVerifications", successfulVerifications);
        payload.put("failedVerifications", failedVerifications);
        payload.put("unresolvedRisks", unresolvedRisks);
        payload.put("criteria", criteria.stream().map(Criterion::toPayload).toList());
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
            code = limit(code); label = limit(label); detail = limit(detail);
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
