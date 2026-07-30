package com.labex.labexagent.context;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 估算一次真实 Provider 请求的完整 token 占用。
 *
 * <p>估算输入使用完整 JSON，而不是只统计 content，因此 role、name、tool_call_id、
 * tool_calls 参数和工具结果都会进入预算。Provider 实际 usage 到达后仍应作为更高优先级证据。</p>
 */
@Service
public final class AgentRequestTokenEstimator {
    private static final Gson GSON = new Gson();
    private static final int REQUEST_ENVELOPE_TOKENS = 12;
    private static final int MESSAGE_ENVELOPE_TOKENS = 4;

    public Estimate estimate(String systemPrompt,
                             Object toolDefinitions,
                             List<Map<String, Object>> messages,
                             Integer contextWindowTokens,
                             Integer reservedOutputTokens) {
        if (contextWindowTokens == null || contextWindowTokens <= 0) {
            throw new AgentContextOverflowException("context_window_unconfigured",
                    "Model context window must be configured before provider invocation");
        }
        int reserve = reservedOutputTokens == null ? 0 : Math.max(0, reservedOutputTokens);
        if (reserve >= contextWindowTokens) {
            throw new AgentContextOverflowException("output_reserve_exhausts_context_window",
                    "Reserved output tokens leave no capacity for provider input");
        }
        int systemTokens = estimateValue(systemPrompt);
        int toolTokens = estimateValue(toolDefinitions);
        int messageTokens = estimateMessages(messages);
        int inputTokens = REQUEST_ENVELOPE_TOKENS + systemTokens + toolTokens + messageTokens;
        int inputCapacity = contextWindowTokens - reserve;
        return new Estimate(systemTokens, toolTokens, messageTokens, reserve, inputTokens,
                inputTokens + reserve, contextWindowTokens, inputCapacity, inputTokens > inputCapacity);
    }

    public int estimateMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map<String, Object> message : messages) {
            total += MESSAGE_ENVELOPE_TOKENS + estimateValue(message);
        }
        return total;
    }

    public int estimateValue(Object value) {
        if (value == null) {
            return 0;
        }
        String serialized = value instanceof String text ? text : GSON.toJson(value);
        if (serialized.isBlank()) {
            return 0;
        }
        int utf8Bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (int) Math.ceil(utf8Bytes / 3.0));
    }

    public record Estimate(int systemPromptTokens,
                           int toolSchemaTokens,
                           int messageTokens,
                           int reservedOutputTokens,
                           int inputTokens,
                           int totalWithReservedOutputTokens,
                           int contextWindowTokens,
                           int inputCapacityTokens,
                           boolean overflowsInputCapacity) {
    }
}