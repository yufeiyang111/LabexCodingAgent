package com.labex.labexagent.llm.langchain4j;

import com.google.gson.Gson;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts LabexAgent standard message maps into LangChain4j ChatMessage hierarchy.
 */
public final class Langchain4jMessageConverter {
    private static final Gson GSON = new Gson();
    private static final Pattern UNPAIRED_SURROGATE = Pattern.compile(
            "[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]");

    private Langchain4jMessageConverter() {}

    /**
     * Sanitizes unpaired UTF-16 surrogates to prevent downstream serialization/API errors.
     */
    public static String sanitizeSurrogates(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return UNPAIRED_SURROGATE.matcher(input).replaceAll("\uFFFD");
    }

    /**
     * Converts a system prompt and a list of message maps into LangChain4j ChatMessages.
     */
    public static List<ChatMessage> convert(String sysPrompt, List<Map<String, Object>> msgs) {
        List<ChatMessage> result = new ArrayList<>();

        if (sysPrompt != null && !sysPrompt.isBlank()) {
            result.add(SystemMessage.from(sanitizeSurrogates(sysPrompt.trim())));
        }

        if (msgs == null || msgs.isEmpty()) {
            return result;
        }

        for (Map<String, Object> msg : msgs) {
            if (msg == null || msg.isEmpty()) {
                continue;
            }
            String role = String.valueOf(msg.getOrDefault("role", "")).toLowerCase();
            Object rawContent = msg.get("content");
            String content = rawContent == null ? "" : sanitizeSurrogates(String.valueOf(rawContent));

            switch (role) {
                case "system":
                    if (!content.isBlank()) {
                        result.add(SystemMessage.from(content));
                    }
                    break;

                case "user":
                    result.add(UserMessage.from(content));
                    break;

                case "assistant":
                    List<ToolExecutionRequest> toolRequests = extractToolRequests(msg);
                    if (!toolRequests.isEmpty()) {
                        if (!content.isBlank()) {
                            result.add(AiMessage.from(content, toolRequests));
                        } else {
                            result.add(AiMessage.from(toolRequests));
                        }
                    } else {
                        result.add(AiMessage.from(content));
                    }
                    break;

                case "tool":
                    String toolCallId = String.valueOf(msg.getOrDefault("tool_call_id", ""));
                    String toolName = String.valueOf(msg.getOrDefault("name", ""));
                    result.add(ToolExecutionResultMessage.from(toolCallId, toolName, content));
                    break;

                default:
                    // Treat any unknown role as user message fallback
                    if (!content.isBlank()) {
                        result.add(UserMessage.from(content));
                    }
                    break;
            }
        }

        return result;
    }

    private static List<ToolExecutionRequest> extractToolRequests(Map<String, Object> msg) {
        Object rawCalls = msg.get("tool_calls");
        if (!(rawCalls instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> callMap)) {
                continue;
            }
            Object idObj = callMap.get("id");
            String id = idObj != null ? String.valueOf(idObj) : "";
            Object rawFn = callMap.get("function");
            String name = "";
            String args = "{}";

            if (rawFn instanceof Map<?, ?> fnMap) {
                Object fnName = fnMap.get("name");
                name = fnName != null ? String.valueOf(fnName) : "";
                Object rawArgs = fnMap.get("arguments");
                if (rawArgs instanceof String s) {
                    args = s;
                } else if (rawArgs != null) {
                    args = GSON.toJson(rawArgs);
                }
            } else if (callMap.containsKey("name")) {
                Object callName = callMap.get("name");
                name = callName != null ? String.valueOf(callName) : "";
                Object rawArgs = callMap.get("arguments");
                if (rawArgs instanceof String s) {
                    args = s;
                } else if (rawArgs != null) {
                    args = GSON.toJson(rawArgs);
                }
            }

            if (!name.isBlank()) {
                requests.add(ToolExecutionRequest.builder()
                        .id(id.isBlank() ? "call_" + System.nanoTime() : id)
                        .name(name)
                        .arguments(args)
                        .build());
            }
        }
        return requests;
    }
}
