package com.labex.labexagent.run;

import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentSubagent;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 执行子 Agent 的模型流，并只把用户可见文本投影到父任务。 */
@Component
public class LlmSubagentExecutor implements SubagentExecutor {
    private final LlmProviderFactory providers;
    private final AgentModelConfigService configs;
    private final AgentSubagentEventService events;

    public LlmSubagentExecutor(LlmProviderFactory providers,
                               AgentModelConfigService configs,
                               AgentSubagentEventService events) {
        this.providers = providers;
        this.configs = configs;
        this.events = events;
    }

    @Override
    public String execute(AgentContext context, AgentSubagent subagent) {
        if (subagent.getInstructions() == null || subagent.getInstructions().isBlank()) {
            throw new IllegalArgumentException("subagent instructions are required");
        }

        AgentModelConfig config = configs.resolveForStudent(
                context.getStudentId(), subagent.getModelConfigId());
        LlmProvider provider = providers.resolveProvider(config);
        StringBuilder output = new StringBuilder();
        AtomicReference<String> failure = new AtomicReference<>();
        boolean[] terminalEvent = {false};

        Consumer<String> publishVisible = delta -> {
            if (delta == null || delta.isEmpty()) return;
            output.append(delta);
            events.append(subagent.getSubagentId(), "DELTA", delta);
        };
        InternalReasoningBoundary.VisibleStreamFilter visibleFilter =
                new InternalReasoningBoundary.VisibleStreamFilter(ignored -> { }, publishVisible);
        CancellationToken cancellationToken = context.getCancellationToken() == null
                ? CancellationToken.none() : context.getCancellationToken();

        provider.chatStream(
                "You are a focused subagent. Return a concise evidence-based result.",
                List.of(Map.of("role", "user", "content", subagent.getInstructions())),
                List.of(),
                providers.buildConfig(config),
                cancellationToken,
                chunk -> {
                    switch (chunk.eventType()) {
                        case TEXT_DELTA -> visibleFilter.push(chunk.content());
                        case THINKING_DELTA, USAGE -> {
                            // 内部推理和计量事件不能进入子 Agent 的可见摘要。
                        }
                        case DONE -> {
                            visibleFilter.flush();
                            terminalEvent[0] = true;
                        }
                        case ERROR -> {
                            terminalEvent[0] = true;
                            failure.compareAndSet(null, safeFailure(
                                    chunk.content(), "Subagent provider returned an unspecified error"));
                        }
                        case CANCELLED -> {
                            terminalEvent[0] = true;
                            failure.compareAndSet(null, "Subagent execution was cancelled");
                        }
                        case TOOL_ARGUMENTS_DELTA, TOOL_CALL -> {
                            terminalEvent[0] = true;
                            failure.compareAndSet(null,
                                    "Subagent provider returned an unexpected tool call");
                        }
                        case UNKNOWN -> {
                            // 未知增量不进入可见输出；缺失终态会在流结束后被明确拒绝。
                        }
                    }
                });

        if (!terminalEvent[0] && failure.get() == null) {
            failure.set("Subagent provider stream ended before a terminal event");
        }
        if (failure.get() != null) {
            String message = safeFailure(failure.get(), "Subagent execution failed");
            events.append(subagent.getSubagentId(), "ERROR", message);
            throw new IllegalStateException(message);
        }
        return output.toString();
    }

    private static String safeFailure(String value, String fallback) {
        String safe = InternalReasoningBoundary.stripVisible(value);
        return safe == null || safe.isBlank() ? fallback : safe;
    }
}