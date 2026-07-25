package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptCacheKeyFactoryStablePrefixTest {

    @Test
    void derivesAStableModelScopedKeyFromTheStaticPromptPrefix() {
        String first = PromptCacheKeyFactory.forStablePrefix(
                42, 7, "https://api.example.test/v1", "model-a", "system prompt", "[tool-schema]");
        String repeated = PromptCacheKeyFactory.forStablePrefix(
                42, 7, "https://api.example.test/v1", "model-a", "system prompt", "[tool-schema]");
        String differentModel = PromptCacheKeyFactory.forStablePrefix(
                42, 8, "https://api.example.test/v1", "model-b", "system prompt", "[tool-schema]");

        assertEquals(first, repeated);
        assertNotEquals(first, differentModel);
        assertTrue(first.startsWith("labex-"));
    }
}
