package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptCacheKeyFactoryTest {

    @Test
    void derivesStableOpaqueKeyPerConversation() {
        String first = PromptCacheKeyFactory.forConversation("conv-sensitive-id");
        String second = PromptCacheKeyFactory.forConversation("conv-sensitive-id");
        String different = PromptCacheKeyFactory.forConversation("another-conversation");

        assertEquals(first, second);
        assertNotEquals(first, different);
        assertTrue(first.startsWith("labex-"));
        assertFalse(first.contains("conv-sensitive-id"));
    }
}
