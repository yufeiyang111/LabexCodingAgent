package com.labex.labexagent.runtime;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContextUsageSnapshot {
    private final String conversationId;
    private final String sessionId;
    private final String provider;
    private final String model;
    private final Integer contextWindowTokens;
    private final int usedTokens;
    private final Integer usagePercent;
    private final Map<String, Integer> categories;
    private final String trimState;
    private final List<ContextPreviewSection> previewSections;
    private final String previewSource;
    private final Map<String, Object> previewMetadata;
    private final LocalDateTime updatedAt;

    public ContextUsageSnapshot(String conversationId, String sessionId, String provider, String model,
                                Integer contextWindowTokens, Map<String, Integer> categories, String trimState) {
        this(conversationId, sessionId, provider, model, contextWindowTokens, categories, trimState, List.of(),
                "UNAVAILABLE", Map.of());
    }

    public ContextUsageSnapshot(String conversationId, String sessionId, String provider, String model,
                                Integer contextWindowTokens, Map<String, Integer> categories, String trimState,
                                List<ContextPreviewSection> previewSections) {
        this(conversationId, sessionId, provider, model, contextWindowTokens, categories, trimState, previewSections,
                previewSections == null || previewSections.isEmpty() ? "UNAVAILABLE" : "LAST_ACTUAL_REQUEST", Map.of());
    }

    public ContextUsageSnapshot(String conversationId, String sessionId, String provider, String model,
                                Integer contextWindowTokens, Map<String, Integer> categories, String trimState,
                                List<ContextPreviewSection> previewSections, String previewSource,
                                Map<String, Object> previewMetadata) {
        this.conversationId = conversationId;
        this.sessionId = sessionId;
        this.provider = provider;
        this.model = model;
        this.contextWindowTokens = contextWindowTokens != null && contextWindowTokens > 0 ? contextWindowTokens : null;
        this.categories = Map.copyOf(new LinkedHashMap<>(categories));
        this.usedTokens = this.categories.values().stream().mapToInt(Integer::intValue).sum();
        this.usagePercent = this.contextWindowTokens == null ? null
                : Math.min(100, (int) Math.round(this.usedTokens * 100.0 / this.contextWindowTokens));
        this.trimState = trimState == null ? "NONE" : trimState;
        this.previewSections = List.copyOf(previewSections == null ? List.of() : previewSections);
        this.previewSource = previewSource == null || previewSource.isBlank()
                ? (this.previewSections.isEmpty() ? "UNAVAILABLE" : "LAST_ACTUAL_REQUEST") : previewSource;
        this.previewMetadata = Map.copyOf(previewMetadata == null ? Map.of() : new LinkedHashMap<>(previewMetadata));
        this.updatedAt = LocalDateTime.now();
    }

    public Map<String, Object> toPreviewPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("sessionId", sessionId);
        payload.put("provider", provider);
        payload.put("model", model);
        payload.put("contextWindowTokens", contextWindowTokens);
        payload.put("usedTokens", usedTokens);
        payload.put("previewSource", previewSource);
        payload.put("previewMetadata", previewMetadata);
        payload.put("updatedAt", updatedAt.toString());
        payload.put("previewSections", previewSections.stream().map(ContextPreviewSection::toPayload).toList());
        return payload;
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("sessionId", sessionId);
        payload.put("provider", provider);
        payload.put("model", model);
        payload.put("contextWindowTokens", contextWindowTokens);
        payload.put("usedTokens", usedTokens);
        payload.put("usagePercent", usagePercent);
        payload.put("measurementSource", "ESTIMATED_CHARS");
        payload.put("categories", categories);
        payload.put("trimState", trimState);
        payload.put("updatedAt", updatedAt.toString());
        payload.put("previewSource", previewSource);
        payload.put("previewMetadata", previewMetadata);
        payload.put("previewSections", previewSections.stream().map(ContextPreviewSection::toPayload).toList());
        return payload;
    }
}
