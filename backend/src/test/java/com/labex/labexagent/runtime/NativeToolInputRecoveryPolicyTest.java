package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeToolInputRecoveryPolicyTest {

    @Test
    void stopsWhenTheSameRejectedArgumentsReturnAfterAValidRound() {
        LabexNativeToolBatchExecutor.Admission rejected = rejectedAdmission(
                "web_fetch", "call-fetch-1", "https://example.test", "numResults", "unknown_field");
        LabexNativeToolBatchExecutor.Admission valid = allowedAdmission("web_search", "call-search-1");
        NativeToolInputRecoveryPolicy.State first = NativeToolInputRecoveryPolicy.advance(
                NativeToolInputRecoveryPolicy.State.initial(), List.of(rejected));
        NativeToolInputRecoveryPolicy.State afterProgress = NativeToolInputRecoveryPolicy.advance(
                first, List.of(valid));
        NativeToolInputRecoveryPolicy.State repeated = NativeToolInputRecoveryPolicy.advance(
                afterProgress, List.of(rejected));

        assertThat(first.rejectedRounds()).isEqualTo(1);
        assertThat(afterProgress.rejectedRounds()).isZero();
        assertThat(repeated.repeatedRejectedBatches()).isEqualTo(2);
        assertThat(NativeToolInputRecoveryPolicy.exhausted(repeated, 2)).isTrue();
    }

    @Test
    void differentRejectedArgumentsRemainRecoverable() {
        LabexNativeToolBatchExecutor.Admission first = rejectedAdmission(
                "web_fetch", "call-fetch-1", "https://example.test", "numResults", "unknown_field");
        LabexNativeToolBatchExecutor.Admission second = rejectedAdmission(
                "web_fetch", "call-fetch-2", "https://example.test", "max_chars", "missing_required");

        NativeToolInputRecoveryPolicy.State state = NativeToolInputRecoveryPolicy.advance(
                NativeToolInputRecoveryPolicy.State.initial(), List.of(first));
        state = NativeToolInputRecoveryPolicy.advance(state, List.of(second));

        assertThat(state.rejectedRounds()).isEqualTo(2);
        assertThat(state.repeatedRejectedBatches()).isEqualTo(1);
        assertThat(NativeToolInputRecoveryPolicy.exhausted(state, 3)).isFalse();
    }

    private LabexNativeToolBatchExecutor.Admission rejectedAdmission(
            String toolName, String toolCallId, String url, String invalidField, String reasonCode) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("url", url);
        arguments.addProperty(invalidField, 6);
        AgentTool tool = tool(toolName);
        return new LabexNativeToolBatchExecutor.Admission(
                new AgentModelTurnExecutor.NativeToolCall(toolName, arguments.toString(), toolCallId, 0),
                AgentToolTurnExecutor.ToolInputResolution.rejected(
                        arguments, ToolResult.failed("invalid " + invalidField), reasonCode), arguments);
    }

    private LabexNativeToolBatchExecutor.Admission allowedAdmission(String toolName, String toolCallId) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", "agent runtime");
        AgentTool tool = tool(toolName);
        return new LabexNativeToolBatchExecutor.Admission(
                new AgentModelTurnExecutor.NativeToolCall(toolName, arguments.toString(), toolCallId, 0),
                AgentToolTurnExecutor.ToolInputResolution.allowed(arguments, tool), arguments);
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(name).description(name).build();
            }

            @Override
            public ToolResult execute(AgentContext context, JsonObject arguments) {
                return ToolResult.ok("ok");
            }
        };
    }
}
