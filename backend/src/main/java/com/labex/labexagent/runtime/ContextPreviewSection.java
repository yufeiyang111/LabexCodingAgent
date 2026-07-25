package com.labex.labexagent.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

public record ContextPreviewSection(String key, String content, int estimatedTokens, boolean truncated) {
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("content", content);
        payload.put("estimatedTokens", estimatedTokens);
        payload.put("truncated", truncated);
        return payload;
    }
}
