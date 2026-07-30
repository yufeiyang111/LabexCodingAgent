package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentProviderProtocolValidatorTest {

    private final AgentProviderProtocolValidator validator = new AgentProviderProtocolValidator();

    @Test
    void rejectsMissingArgumentsAndDuplicateToolIdsAcrossCompletedBatches() {
        Map<String, Object> missingArguments = new LinkedHashMap<>();
        missingArguments.put("id", "call-missing-args");
        missingArguments.put("type", "function");
        missingArguments.put("function", Map.of("name", "read_file", "arguments", ""));

        Map<String, Object> duplicate = new LinkedHashMap<>();
        duplicate.put("id", "call-duplicate");
        duplicate.put("type", "function");
        duplicate.put("function", Map.of("name", "list_files", "arguments", "{}"));

        List<String> errors = validator.validate(List.of(
                Map.of("role", "assistant", "tool_calls", List.of(missingArguments)),
                Map.of("role", "tool", "tool_call_id", "call-missing-args", "name", "read_file", "content", "ok"),
                Map.of("role", "assistant", "tool_calls", List.of(duplicate)),
                Map.of("role", "tool", "tool_call_id", "call-duplicate", "name", "list_files", "content", "ok"),
                Map.of("role", "assistant", "tool_calls", List.of(duplicate)),
                Map.of("role", "tool", "tool_call_id", "call-duplicate", "name", "list_files", "content", "ok")));

        assertThat(errors).anyMatch(error -> error.contains("arguments is required"));
        assertThat(errors).anyMatch(error -> error.contains("duplicate tool call id: call-duplicate"));
    }

    @Test
    void rejectsToolResultBeforeItsAssistantCallAndMissingToolName() {
        List<String> errors = validator.validate(List.of(
                Map.of("role", "tool", "tool_call_id", "call-orphan", "name", "", "content", "orphan"),
                Map.of("role", "assistant", "tool_calls", List.of(
                        Map.of("id", "call-later", "type", "function",
                                "function", Map.of("name", "read_file", "arguments", "{}"))))));

        assertThat(errors).anyMatch(error -> error.contains("no pending tool call: call-orphan"));
        assertThat(errors).anyMatch(error -> error.contains("tool name is required"));
        assertThat(errors).anyMatch(error -> error.contains("no result: call-later"));
    }
}
