package com.labex.labexagent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Converts every native tool call in one model turn into OpenAI-compatible protocol messages.
 * Execution remains deterministic and serial so writes and approvals cannot race each other.
 */
@Service
public final class AgentToolCallBatchProtocol {

    public String validateIdentity(AgentModelTurnExecutor.NativeToolCall call) {
        if (call == null) return "toolCall is required";
        String toolCallIdError = AgentToolCallIdPolicy.validationError(call.toolCallId());
        if (!toolCallIdError.isEmpty()) return toolCallIdError;
        if (call.toolName() == null || call.toolName().isBlank()) return "toolName is required";
        return "";
    }

    public Map<String, Object> assistantMessage(
            String content, List<AgentModelTurnExecutor.NativeToolCall> calls) {
        LinkedHashMap<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content == null ? "" : content);
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        if (calls != null) {
            for (AgentModelTurnExecutor.NativeToolCall call : calls) {
                LinkedHashMap<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.toolName());
                function.put("arguments", call.toolArguments() == null ? "" : call.toolArguments());
                LinkedHashMap<String, Object> toolCall = new LinkedHashMap<>();
                toolCall.put("id", call.toolCallId());
                toolCall.put("type", "function");
                toolCall.put("function", function);
                toolCalls.add(toolCall);
            }
        }
        message.put("tool_calls", toolCalls);
        return message;
    }

    public Map<String, Object> toolResultMessage(
            AgentModelTurnExecutor.NativeToolCall call, String content) {
        LinkedHashMap<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", call.toolCallId());
        message.put("name", call.toolName());
        message.put("content", content == null ? "" : content);
        return message;
    }
}
