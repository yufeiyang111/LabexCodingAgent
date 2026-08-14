package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptCacheKeyFactoryConversationRoutingTest {

    @Test
    void derivesStableOpaqueKeyForOneConversationAndModelRoute() {
        String first = PromptCacheKeyFactory.forConversation(
                42, 7, "https://api.example.test/v1", "model-a", "conv-sensitive-id");
        String repeated = PromptCacheKeyFactory.forConversation(
                42, 7, "https://api.example.test/v1", "model-a", "conv-sensitive-id");
        String differentConversation = PromptCacheKeyFactory.forConversation(
                42, 7, "https://api.example.test/v1", "model-a", "another-conversation");
        String differentModelRoute = PromptCacheKeyFactory.forConversation(
                42, 8, "https://api.example.test/v1", "model-b", "conv-sensitive-id");

        assertEquals(first, repeated);
        assertNotEquals(first, differentConversation);
        assertNotEquals(first, differentModelRoute);
        assertTrue(first.startsWith("labex-"));
        assertFalse(first.contains("conv-sensitive-id"));
    }
}
