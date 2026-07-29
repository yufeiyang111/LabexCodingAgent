package com.labex.labexagent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

public record ContextBudgetBreakdown(
        int contextWindowTokens,
        int inputCapacityTokens,
        int reservedOutputTokens,
        Map<String, Integer> staticCategories,
        Map<String, Integer> reducibleCategories,
        int softLimitTokens) {

    public ContextBudgetBreakdown(int contextWindowTokens, int inputCapacityTokens, int reservedOutputTokens,
                                  Map<String, Integer> staticCategories,
                                  Map<String, Integer> reducibleCategories) {
        this(contextWindowTokens, inputCapacityTokens, reservedOutputTokens,
                staticCategories, reducibleCategories, inputCapacityTokens);
    }

    public ContextBudgetBreakdown {
        contextWindowTokens = Math.max(0, contextWindowTokens);
        inputCapacityTokens = Math.max(0, inputCapacityTokens);
        reservedOutputTokens = Math.max(0, reservedOutputTokens);
        softLimitTokens = Math.max(0, Math.min(inputCapacityTokens, softLimitTokens));
        staticCategories = normalized(staticCategories);
        reducibleCategories = normalized(reducibleCategories);
    }

    public int staticTokens() {
        return staticCategories.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int reducibleTokens() {
        return reducibleCategories.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int totalInputTokens() {
        return staticTokens() + reducibleTokens();
    }

    public int totalWithReservedOutputTokens() {
        return totalInputTokens() + reservedOutputTokens;
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contextWindowTokens", contextWindowTokens);
        payload.put("inputCapacityTokens", inputCapacityTokens);
        payload.put("reservedOutputTokens", reservedOutputTokens);
        payload.put("softLimitTokens", softLimitTokens);
        payload.put("staticTokens", staticTokens());
        payload.put("reducibleTokens", reducibleTokens());
        payload.put("totalInputTokens", totalInputTokens());
        payload.put("totalWithReservedOutputTokens", totalWithReservedOutputTokens());
        payload.put("staticCategories", staticCategories);
        payload.put("reducibleCategories", reducibleCategories);
        return payload;
    }

    private static Map<String, Integer> normalized(Map<String, Integer> source) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null && !key.isBlank()) result.put(key, Math.max(0, value == null ? 0 : value));
            });
        }
        return Map.copyOf(result);
    }
}
