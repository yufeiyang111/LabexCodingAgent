package com.labex.labexagent.llm.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Langchain4jMessageConverterTest {

    @Test
    void convertsSystemPromptAndStandardUserAssistantMessages() {
        String sysPrompt = "You are a helpful coding assistant.";
        List<Map<String, Object>> msgs = List.of(
                Map.of("role", "user", "content", "Hello!"),
                Map.of("role", "assistant", "content", "Hi there! How can I help you today?")
        );

        List<ChatMessage> result = Langchain4jMessageConverter.convert(sysPrompt, msgs);

        assertEquals(3, result.size());
        assertTrue(result.get(0) instanceof SystemMessage);
        assertEquals("You are a helpful coding assistant.", ((SystemMessage) result.get(0)).text());

        assertTrue(result.get(1) instanceof UserMessage);
        assertEquals("Hello!", ((UserMessage) result.get(1)).singleText());

        assertTrue(result.get(2) instanceof AiMessage);
        assertEquals("Hi there! How can I help you today?", ((AiMessage) result.get(2)).text());
    }

    @Test
    void convertsAssistantToolCallsAndToolResults() {
        List<Map<String, Object>> msgs = List.of(
                Map.of("role", "assistant", "content", "Let me check the file.",
                        "tool_calls", List.of(
                                Map.of(
                                        "id", "call_read_123",
                                        "type", "function",
                                        "function", Map.of(
                                                "name", "read_file",
                                                "arguments", "{\"path\":\"src/main.rs\"}"
                                        )
                                )
                        )),
                Map.of(
                        "role", "tool",
                        "tool_call_id", "call_read_123",
                        "name", "read_file",
                        "content", "fn main() { println!(\"Hello\"); }"
                )
        );

        List<ChatMessage> result = Langchain4jMessageConverter.convert(null, msgs);

        assertEquals(2, result.size());

        assertTrue(result.get(0) instanceof AiMessage);
        AiMessage aiMessage = (AiMessage) result.get(0);
        assertTrue(aiMessage.hasToolExecutionRequests());
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        ToolExecutionRequest req = aiMessage.toolExecutionRequests().get(0);
        assertEquals("call_read_123", req.id());
        assertEquals("read_file", req.name());
        assertEquals("{\"path\":\"src/main.rs\"}", req.arguments());

        assertTrue(result.get(1) instanceof ToolExecutionResultMessage);
        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) result.get(1);
        assertEquals("call_read_123", toolResult.id());
        assertEquals("read_file", toolResult.toolName());
        assertEquals("fn main() { println!(\"Hello\"); }", toolResult.text());
    }

    @Test
    void sanitizesUnpairedSurrogates() {
        String invalidSurrogate = "Invalid \uD800 text";
        String sanitized = Langchain4jMessageConverter.sanitizeSurrogates(invalidSurrogate);
        assertFalse(sanitized.contains("\uD800"));
        assertTrue(sanitized.contains("\uFFFD"));
    }
}
