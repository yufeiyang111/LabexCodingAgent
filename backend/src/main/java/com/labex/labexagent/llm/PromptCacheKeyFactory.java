package com.labex.labexagent.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Produces stable, non-reversible provider cache-routing keys per conversation. */
public final class PromptCacheKeyFactory {
    private PromptCacheKeyFactory() {
    }

    /**
     * 生成会话与模型路由范围内稳定的脱敏缓存路由 key。
     * 实际请求内容仍是 Provider 判断缓存匹配的唯一依据；该 key 只用于提高缓存分片亲和性。
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
