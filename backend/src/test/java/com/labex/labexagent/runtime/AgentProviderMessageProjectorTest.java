package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentProviderMessageProjectorTest {
    private final AgentProviderProtocolValidator validator = new AgentProviderProtocolValidator();
    private final AgentProviderMessageProjector projector = new AgentProviderMessageProjector(validator);

    @Test
    void acceptsMultipleNativeToolCallsOnlyWhenEveryResultIsCorrelated() {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        assistant.put("tool_calls", List.of(
                toolCall("call-read", "read_file", "{}"),
                toolCall("call-list", "list_files", "{}")));
        List<Map<String, Object>> projected = projector.project(List.of(
                Map.of("role", "user", "content", "inspect"),
                assistant,
                toolResult("call-read", "read_file", "ok"),
                toolResult("call-list", "list_files", "ok")));

        assertThat(projected).hasSize(4);
        assertThat(projected.get(1)).containsKey("tool_calls");
        assertThat(projected.get(2)).containsEntry("tool_call_id", "call-read");
        assertThat(projected.get(3)).containsEntry("tool_call_id", "call-list");
        assertThat(validator.validate(projected)).isEmpty();
    }

    @Test
    void rejectsOrphanToolResultAndUnclosedToolCall() {
        assertThatThrownBy(() -> projector.project(List.of(
                toolResult("call-orphan", "read_file", "orphan"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pending tool call");

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        assistant.put("tool_calls", List.of(toolCall("call-open", "read_file", "{}")));
        assertThatThrownBy(() -> projector.project(List.of(assistant)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no result");
    }

    @Test
    void deepCopiesNestedToolCallStructure() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "read_file");
        function.put("arguments", "{}");
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call-1");
        call.put("type", "function");
        call.put("function", function);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        assistant.put("tool_calls", List.of(call));

        List<Map<String, Object>> projected = projector.project(List.of(
                assistant,
                toolResult("call-1", "read_file", "ok")));
        function.put("name", "mutated");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) projected.get(0).get("tool_calls");
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedFunction = (Map<String, Object>) calls.get(0).get("function");
        assertThat(projectedFunction.get("name")).isEqualTo("read_file");
    }

    private Map<String, Object> toolCall(String id, String name, String arguments) {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", arguments));
    }

    private Map<String, Object> toolResult(String id, String name, String content) {
        return Map.of("role", "tool", "tool_call_id", id, "name", name, "content", content);
    }
}