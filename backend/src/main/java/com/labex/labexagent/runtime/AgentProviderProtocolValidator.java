package com.labex.labexagent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 校验发送给 Provider 的原生工具调用协议，避免裁剪或恢复后产生孤立工具结果。 */
public final class AgentProviderProtocolValidator {

    public List<String> validate(List<Map<String, Object>> messages) {
        List<String> errors = new ArrayList<>();
        if (messages == null) {
            errors.add("messages is required");
            return errors;
        }
        Set<String> pendingToolCalls = new LinkedHashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            Map<String, Object> message = messages.get(index);
            if (message == null) {
                errors.add("message[" + index + "] is null");
                continue;
            }
            String role = stringValue(message.get("role"));
            if (role.isBlank()) {
                errors.add("message[" + index + "] role is required");
                continue;
            }
            if ("assistant".equalsIgnoreCase(role)) {
                validateAssistantToolCalls(index, message.get("tool_calls"), pendingToolCalls, errors);
            } else if ("tool".equalsIgnoreCase(role)) {
                String toolCallId = stringValue(message.get("tool_call_id"));
                String toolName = stringValue(message.get("name"));
                if (toolCallId.isBlank()) {
                    errors.add("message[" + index + "] tool_call_id is required");
                } else if (!pendingToolCalls.remove(toolCallId)) {
                    errors.add("message[" + index + "] has no pending tool call: " + toolCallId);
                }
                if (toolName.isBlank()) {
                    errors.add("message[" + index + "] tool name is required");
                }
            }
        }
        for (String toolCallId : pendingToolCalls) {
            errors.add("tool call has no result: " + toolCallId);
        }
        return List.copyOf(errors);
    }

    public void validateOrThrow(List<Map<String, Object>> messages) {
        List<String> errors = validate(messages);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid Provider transcript: " + String.join("; ", errors));
        }
    }

    private void validateAssistantToolCalls(int index, Object rawToolCalls,
                                            Set<String> pendingToolCalls, List<String> errors) {
        if (rawToolCalls == null) {
            return;
        }
        if (!(rawToolCalls instanceof List<?> toolCalls)) {
            errors.add("message[" + index + "] tool_calls must be a list");
            return;
        }
        for (int callIndex = 0; callIndex < toolCalls.size(); callIndex++) {
            Object rawCall = toolCalls.get(callIndex);
            if (!(rawCall instanceof Map<?, ?> call)) {
                errors.add("message[" + index + "] tool_calls[" + callIndex + "] must be an object");
                continue;
            }
            String id = stringValue(call.get("id"));
            if (id.isBlank()) {
                errors.add("message[" + index + "] tool_calls[" + callIndex + "] id is required");
                continue;
            }
            if (!pendingToolCalls.add(id)) {
                errors.add("duplicate tool call id: " + id);
            }
            Object function = call.get("function");
            if (!(function instanceof Map<?, ?> functionMap)
                    || stringValue(functionMap.get("name")).isBlank()) {
                errors.add("message[" + index + "] tool_calls[" + callIndex + "] function.name is required");
            }
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}