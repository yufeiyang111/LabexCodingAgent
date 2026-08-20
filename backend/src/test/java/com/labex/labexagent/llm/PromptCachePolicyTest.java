package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptCachePolicyTest {

    @Test
    void injectsCacheControlToSystemAndLatestUserMessage() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "System Prompt"),
                Map.of("role", "user", "content", "First user request"),
                Map.of("role", "assistant", "content", "Thinking and tool call"),
                Map.of("role", "tool", "content", "Tool output"),
                Map.of("role", "user", "content", "Second (latest) user request"),
                Map.of("role", "assistant", "content", "Second response")
        );

        List<Map<String, Object>> result = PromptCachePolicy.applyToMessages(messages);

        assertEquals(6, result.size());

        // 1. System message has cache_control
        assertTrue(result.get(0).containsKey("cache_control"));
        assertEquals(Map.of("type", "ephemeral"), result.get(0).get("cache_control"));

        // 2. First user message does NOT have cache_control
        assertFalse(result.get(1).containsKey("cache_control"));

        // 3. Assistant and tool messages do NOT have cache_control
        assertFalse(result.get(2).containsKey("cache_control"));
        assertFalse(result.get(3).containsKey("cache_control"));

        // 4. Latest user message (index 4) has cache_control
        assertTrue(result.get(4).containsKey("cache_control"));
        assertEquals(Map.of("type", "ephemeral"), result.get(4).get("cache_control"));

        // 5. Subsequent assistant message does not
        assertFalse(result.get(5).containsKey("cache_control"));
    }

    @Test
    void injectsCacheControlToLastToolDefinition() {
        List<Map<String, Object>> tools = List.of(
                Map.of("type", "function", "function", Map.of("name", "read_file")),
                Map.of("type", "function", "function", Map.of("name", "edit_file")),
                Map.of("type", "function", "function", Map.of("name", "shell"))
        );

        List<Map<String, Object>> result = PromptCachePolicy.applyToTools(tools);

        assertEquals(3, result.size());
        assertFalse(result.get(0).containsKey("cache_control"));
        assertFalse(result.get(1).containsKey("cache_control"));
        assertTrue(result.get(2).containsKey("cache_control"));
        assertEquals(Map.of("type", "ephemeral"), result.get(2).get("cache_control"));
    }
}
