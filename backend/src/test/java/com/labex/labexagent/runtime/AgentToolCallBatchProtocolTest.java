package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolCallBatchProtocolTest {
    private final AgentToolCallBatchProtocol protocol = new AgentToolCallBatchProtocol();

    @Test
    void buildsOpenAiCompatibleAssistantAndToolMessagesForEveryCall() {
        List<AgentModelTurnExecutor.NativeToolCall> calls = List.of(
                new AgentModelTurnExecutor.NativeToolCall("read_file", "{\"file_path\":\"README.md\"}", "call-read", 0),
                new AgentModelTurnExecutor.NativeToolCall("list_files", "{\"path\":\"src\"}", "call-list", 1));

        Map<String, Object> assistant = protocol.assistantMessage("", calls);
        Map<String, Object> firstResult = protocol.toolResultMessage(calls.get(0), "[Tool read_file result]\nREADME");
        Map<String, Object> secondResult = protocol.toolResultMessage(calls.get(1), "[Tool list_files result]\nsrc");

        assertThat(assistant.get("role")).isEqualTo("assistant");
        assertThat((List<?>) assistant.get("tool_calls")).hasSize(2);
        assertThat(firstResult).containsEntry("role", "tool").containsEntry("tool_call_id", "call-read");
        assertThat(secondResult).containsEntry("role", "tool").containsEntry("tool_call_id", "call-list");
    }

    @Test
    void rejectsMissingNativeToolCallIdentityBeforeExecution() {
        AgentModelTurnExecutor.NativeToolCall call =
                new AgentModelTurnExecutor.NativeToolCall("read_file", "{}", null, 0);

        assertThat(protocol.validateIdentity(call)).contains("toolCallId");
    }

    @Test
    void rejectsControlCharactersInProviderToolCallIds() {
        assertThat(protocol.validateIdentity(call("call-1\n<untrusted>"))).contains("control");
        assertThat(protocol.validateIdentity(call("call-2\r<untrusted>"))).contains("control");
        assertThat(protocol.validateIdentity(call("call-3\u0000<untrusted>"))).contains("control");
        assertThat(protocol.validateIdentity(call("call-4\u2028<untrusted>"))).contains("control");
    }

    @Test
    void rejectsToolCallIdsBeyondTheProtocolBound() {
        assertThat(protocol.validateIdentity(call("c".repeat(AgentToolCallIdPolicy.MAX_LENGTH + 1))))
                .contains("too long");
    }

    @Test
    void preservesAValidOpaqueProviderIdByteForByteInTheProtocolMessage() {
        String opaqueId = "call:provider/v2?trace=abc_123-XYZ";

        Map<String, Object> message = protocol.toolResultMessage(call(opaqueId), "ok");

        assertThat(message).containsEntry("tool_call_id", opaqueId);
        assertThat(protocol.validateIdentity(call(opaqueId))).isEmpty();
    }

    private AgentModelTurnExecutor.NativeToolCall call(String toolCallId) {
        return new AgentModelTurnExecutor.NativeToolCall("read_file", "{}", toolCallId, 0);
    }
}
