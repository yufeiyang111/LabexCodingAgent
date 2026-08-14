package com.labex.labexagent.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Produces stable, non-reversible provider cache-routing keys per conversation. */
public final class PromptCacheKeyFactory {
    private PromptCacheKeyFactory() {
    }

    public static String forConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return "";
        return digest("conversation", conversationId);
    }

    /**
     * Produces an opaque cache-routing key that stays stable for one conversation and model route.
     * Request content remains the Provider's cache-match authority; this key only improves cache-shard affinity.
     */
    public static String forConversation(Integer studentId, Integer modelConfigId, String baseUrl,
                                         String modelName, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return "";
        return digest("conversation-route-v2",
                String.valueOf(studentId == null ? 0 : studentId),
                String.valueOf(modelConfigId == null ? 0 : modelConfigId),
                normalize(baseUrl), normalize(modelName), normalize(conversationId));
    }

    private static String digest(String... values) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = normalize(value).getBytes(StandardCharsets.UTF_8);
                messageDigest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                messageDigest.update((byte) ':');
                messageDigest.update(bytes);
                messageDigest.update((byte) 0);
            }
            byte[] digest = messageDigest.digest();
            StringBuilder key = new StringBuilder("labex-");
            for (int i = 0; i < 16; i++) {
                key.append(String.format("%02x", digest[i]));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
