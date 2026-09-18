package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 会话标识（OpenCode Go 的 x-opencode-session）必须"一个会话内恒定"。
 *
 * <p>上游 opencode 的语义是 {@code "x-opencode-session": input.sessionID}——纯 session 维度。
 * 因此本类的签名刻意<b>只接受 conversationId</b>：一旦有人把模型路由（modelConfigId / baseUrl /
 * modelName）重新塞回来，这里会直接编译不过，这正是我们要的护栏。
 * 缓存路由 key（{@link PromptCacheKeyFactory#forConversation}）相反，必须按路由分片，两者不能混用。
 */
class PromptCacheKeyFactorySessionIdTest {

    @Test
    void staysConstantForTheWholeConversation() {
        String first = PromptCacheKeyFactory.sessionIdForConversation("conv-abc");
        String repeated = PromptCacheKeyFactory.sessionIdForConversation("conv-abc");

        assertEquals(first, repeated);
        assertTrue(first.startsWith("labex-"));
    }

    @Test
    void differsAcrossConversations() {
        assertNotEquals(
                PromptCacheKeyFactory.sessionIdForConversation("conv-abc"),
                PromptCacheKeyFactory.sessionIdForConversation("conv-def"));
    }

    @Test
    void neverEqualsTheRouteScopedCacheKey() {
        // 两者输入维度不同（会话 vs 会话+模型路由），命名空间也分开，不能出现同一个值被两处复用。
        assertNotEquals(
                PromptCacheKeyFactory.sessionIdForConversation("conv-abc"),
                PromptCacheKeyFactory.forConversation(42, 7, "https://api.example.test/v1", "model-a", "conv-abc"));
    }

    @Test
    void returnsEmptyForMissingConversationSoTheCallerCanFallBack() {
        assertEquals("", PromptCacheKeyFactory.sessionIdForConversation(null));
        assertEquals("", PromptCacheKeyFactory.sessionIdForConversation(""));
        assertEquals("", PromptCacheKeyFactory.sessionIdForConversation("   "));
    }

    @Test
    void keepsConversationIdOutOfTheSentValue() {
        String session = PromptCacheKeyFactory.sessionIdForConversation("conv-sensitive-id");

        assertFalse(session.contains("conv-sensitive-id"));
        assertEquals("labex-".length() + 32, session.length());
    }
}
