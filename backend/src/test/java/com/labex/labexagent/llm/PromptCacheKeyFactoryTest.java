package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PromptCacheKeyFactoryTest {

    @Test
    void leavesCacheRoutingDisabledWithoutConversationId() {
        assertEquals("", PromptCacheKeyFactory.forConversation(
                42, 7, "https://api.example.test/v1", "model-a", " "));
    }
}
