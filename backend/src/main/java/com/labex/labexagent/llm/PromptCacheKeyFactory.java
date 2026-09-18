package com.labex.labexagent.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Produces stable, non-reversible provider identifiers: prompt-cache routing keys (per conversation
 * and model route) and session ids (per conversation only, reported as x-opencode-session).
 */
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

    /**
     * 会话级稳定 session 标识：当前用于 OpenCode Go 要求的 {@code x-opencode-session}
     * （网关据此做路由与 prompt cache 亲和）。
     *
     * <p><b>只按会话身份摘要，刻意不含模型路由</b>——对齐上游 opencode
     * {@code session/llm/request.ts} 的 {@code "x-opencode-session": input.sessionID}：
     * 同一个会话内无论切模型、走 compaction 专用模型、还是 baseUrl 变化，都必须保持同一个值，
     * 否则网关会当成新会话、缓存亲和失效（这正是"一个会话内务必一致"的要求）。
     * 因此这里不复用 {@link #forConversation} 的路由维度——缓存 key 按路由分片是对的，
     * 会话标识按路由分片是错的。
     *
     * <p>{@code AgentConversation.conversationId} 是全局唯一 UUID，摘要后不含明文，
     * 无法反推用户或会话 ID。
     */
    public static String sessionIdForConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return "";
        return digest("conversation-session-v2", normalize(conversationId));
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
