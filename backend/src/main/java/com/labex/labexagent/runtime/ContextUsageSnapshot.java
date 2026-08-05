package com.labex.labexagent.runtime;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContextUsageSnapshot {
    private static final String CURRENT_CATEGORY_VERSION = "context-budget-v2";
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
    private final String contextCategoryVersion;
    private ContextBudgetBreakdown budgetBreakdown;
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
        this(conversationId, sessionId, provider, model, contextWindowTokens, categories, trimState,
                previewSections, previewSource, previewMetadata, CURRENT_CATEGORY_VERSION, LocalDateTime.now());
    }

    private ContextUsageSnapshot(String conversationId, String sessionId, String provider, String model,
                                 Integer contextWindowTokens, Map<String, Integer> categories, String trimState,
                                 List<ContextPreviewSection> previewSections, String previewSource,
                                 Map<String, Object> previewMetadata, String contextCategoryVersion,
                                 LocalDateTime updatedAt) {
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
        this.contextCategoryVersion = contextCategoryVersion == null || contextCategoryVersion.isBlank()
                ? "context-budget-v1" : contextCategoryVersion;
        this.updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
    }

    public ContextUsageSnapshot withBudgetBreakdown(ContextBudgetBreakdown budgetBreakdown) {
        this.budgetBreakdown = budgetBreakdown;
        return this;
    }

    /** \u4ece\u6301\u4e45\u5316 CONTEXT_STATUS \u4e8b\u4ef6\u6062\u590d\u4e0a\u4e0b\u6587\u7528\u91cf\u6295\u5f71\uff0c\u4e0d\u6062\u590d\u4e3a\u65b0\u7684\u4e8b\u5b9e\u6e90\u3002 */
    @SuppressWarnings("unchecked")
    public static ContextUsageSnapshot fromPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        Map<String, Object> rawCategories = payload.get("categories") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Integer> categories = new LinkedHashMap<>();
        rawCategories.forEach((key, value) -> categories.put(String.valueOf(key), number(value)));
        List<ContextPreviewSection> sections = List.of();
        Object rawSections = payload.get("previewSections");
        if (rawSections instanceof List<?> list) {
            sections = list.stream().filter(item -> item instanceof Map<?, ?>).map(item -> {
                Map<String, Object> section = (Map<String, Object>) item;
                return new ContextPreviewSection(
                        String.valueOf(section.getOrDefault("key", "")),
                        String.valueOf(section.getOrDefault("content", "")),
                        number(section.get("estimatedTokens")),
                        Boolean.parseBoolean(String.valueOf(section.getOrDefault("truncated", false))));
            }).toList();
        }
        Map<String, Object> metadata = payload.get("previewMetadata") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        ContextUsageSnapshot snapshot = new ContextUsageSnapshot(
                string(payload.get("conversationId")), string(payload.get("sessionId")),
                string(payload.get("provider")), string(payload.get("model")),
                nullablePositiveNumber(payload.get("contextWindowTokens")), categories,
                string(payload.get("trimState")), sections, string(payload.get("previewSource")), metadata,
                string(payload.get("contextCategoryVersion")), updatedAt(payload.get("updatedAt")));
        Map<String, Integer> staticCategories = integerMap(payload.get("staticCategories"));
        Map<String, Integer> reducibleCategories = integerMap(payload.get("reducibleCategories"));
        if (!staticCategories.isEmpty() || !reducibleCategories.isEmpty()) {
            snapshot.withBudgetBreakdown(new ContextBudgetBreakdown(
                    number(payload.get("contextWindowTokens")), number(payload.get("inputCapacityTokens")),
                    number(payload.get("reservedOutputTokens")), staticCategories, reducibleCategories,
                    number(payload.get("softLimitTokens"))));
        }
        return snapshot;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (RuntimeException ignored) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> integerMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), number(item)));
        return result;
    }

    private static LocalDateTime updatedAt(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            try { return LocalDateTime.parse(text); } catch (RuntimeException ignored) { }
        }
        return LocalDateTime.now();
    }

    private static Integer nullablePositiveNumber(Object value) {
        int number = number(value);
        return number > 0 ? number : null;
    }

    public Map<String, Object> toPreviewPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("sessionId", sessionId);
        payload.put("provider", provider);
        payload.put("model", model);
        payload.put("contextWindowTokens", contextWindowTokens);
        payload.put("usedTokens", usedTokens);
        payload.put("contextCategoryVersion", contextCategoryVersion);
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
        payload.put("contextCategoryVersion", contextCategoryVersion);
        payload.put("categories", categories);
        if (budgetBreakdown != null) {
            payload.put("staticTokens", budgetBreakdown.staticTokens());
            payload.put("reducibleTokens", budgetBreakdown.reducibleTokens());
            payload.put("reservedOutputTokens", budgetBreakdown.reservedOutputTokens());
            payload.put("inputCapacityTokens", budgetBreakdown.inputCapacityTokens());
            payload.put("softLimitTokens", budgetBreakdown.softLimitTokens());
            payload.put("staticCategories", budgetBreakdown.staticCategories());
            payload.put("reducibleCategories", budgetBreakdown.reducibleCategories());
        }
        payload.put("trimState", trimState);
        payload.put("updatedAt", updatedAt.toString());
        payload.put("previewSource", previewSource);
        payload.put("previewMetadata", previewMetadata);
        payload.put("previewSections", previewSections.stream().map(ContextPreviewSection::toPayload).toList());
        return payload;
    }
}
