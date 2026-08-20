package com.labex.labexagent.llm.langchain4j;

import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.ProviderCapabilities;
import com.labex.labexagent.llm.ProviderFailure;
import com.labex.labexagent.llm.ProviderFailureType;
import com.labex.labexagent.runtime.CancellationToken;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * LlmProvider implementation powered by LangChain4j for seamless multi-provider streaming and tool-calling.
 */
@Component
public class Langchain4jChatProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(Langchain4jChatProvider.class);

    private final Langchain4jModelFactory modelFactory;

    public Langchain4jChatProvider() {
        this(new Langchain4jModelFactory());
    }

    @Autowired
    public Langchain4jChatProvider(Langchain4jModelFactory modelFactory) {
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
    }

    @Override
    public String getProviderId() {
        return "langchain4j";
    }

    @Override
    public String getProviderName() {
        return "LangChain4j Unified Driver";
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true, true, true);
    }

    @Override
    public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                              List<Map<String, Object>> tools, LlmConfig config) {
        return chatWithTools(sysPrompt, msgs, tools, config, CancellationToken.none());
    }

    @Override
    public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                              List<Map<String, Object>> tools, LlmConfig config,
                                              CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            return cancelledResponse();
        }

        try {
            ChatLanguageModel model = modelFactory.createSyncModel(config);
            List<ChatMessage> chatMessages = Langchain4jMessageConverter.convert(sysPrompt, msgs);
            List<ToolSpecification> toolSpecs = Langchain4jToolConverter.convert(tools);

            Response<AiMessage> response;
            if (toolSpecs.isEmpty()) {
                response = model.generate(chatMessages);
            } else {
                response = model.generate(chatMessages, toolSpecs);
            }

            if (token.isCancellationRequested()) {
                return cancelledResponse();
            }

            return parseAiMessageResponse(response);
        } catch (Exception e) {
            log.error("Langchain4j sync chat error: {}", e.getMessage(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("type", "error");
            err.put("message", "LLM error: " + e.getMessage());
            err.put("content", "");
            return err;
        }
    }

    @Override
    public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                           List<Map<String, Object>> tools, LlmConfig config,
                           Consumer<StreamChunk> onChunk) {
        chatStream(sysPrompt, msgs, tools, config, CancellationToken.none(), onChunk);
    }

    @Override
    public void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                           List<Map<String, Object>> tools, LlmConfig config,
                           CancellationToken cancellationToken,
                           Consumer<StreamChunk> onChunk) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            emitCancelled(onChunk);
            return;
        }

        try {
            StreamingChatLanguageModel model = modelFactory.createStreamingModel(config);
            List<ChatMessage> chatMessages = Langchain4jMessageConverter.convert(sysPrompt, msgs);
            List<ToolSpecification> toolSpecs = Langchain4jToolConverter.convert(tools);

            StringBuilder contentBuf = new StringBuilder();
            StringBuilder thinkingBuf = new StringBuilder();
            AtomicReference<Map<String, Object>> latestUsage = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean finished = new AtomicBoolean(false);

            try (CancellationToken.Registration ignored = token.onCancellation(() -> {
                if (finished.compareAndSet(false, true)) {
                    emitCancelled(onChunk);
                    latch.countDown();
                }
            })) {
                StreamingResponseHandler<AiMessage> handler = new StreamingResponseHandler<>() {
                    @Override
                    public void onNext(String tokenText) {
                        if (token.isCancellationRequested() || finished.get()) {
                            return;
                        }
                        if (tokenText != null && !tokenText.isEmpty()) {
                            contentBuf.append(tokenText);
                            onChunk.accept(new StreamChunk("text_delta", tokenText, null, null, null, false, null));
                        }
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        if (finished.compareAndSet(false, true)) {
                            AiMessage aiMessage = response != null ? response.content() : null;
                            if (response != null && response.tokenUsage() != null) {
                                Map<String, Object> usage = extractUsage(response.tokenUsage());
                                latestUsage.set(usage);
                                onChunk.accept(new StreamChunk("usage", "", null, null, null, false, usage));
                            }

                            if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
                                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                                for (int idx = 0; idx < requests.size(); idx++) {
                                    ToolExecutionRequest req = requests.get(idx);
                                    String effectiveCallId = (req.id() != null && !req.id().isBlank())
                                            ? req.id()
                                            : "call_" + idx + "_" + Long.toHexString(System.nanoTime());
                                    onChunk.accept(new StreamChunk("tool_call", null, req.name(),
                                            req.arguments(), null, false, latestUsage.get(), effectiveCallId, idx, null));
                                }
                            }

                            onChunk.accept(new StreamChunk("done", contentBuf.toString(), null, null,
                                    thinkingBuf.toString(), true, latestUsage.get()));
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (finished.compareAndSet(false, true)) {
                            log.error("Langchain4j streaming error: {}", error.getMessage(), error);
                            String msg = error.getMessage() != null ? error.getMessage() : "Unknown stream error";
                            ProviderFailure failure = new ProviderFailure(ProviderFailureType.NETWORK, null, msg, true);
                            onChunk.accept(new StreamChunk("error", failure.message(), null, null, null,
                                    true, null, null, null, failure));
                            latch.countDown();
                        }
                    }
                };

                if (toolSpecs.isEmpty()) {
                    model.generate(chatMessages, handler);
                } else {
                    model.generate(chatMessages, toolSpecs, handler);
                }

                // Wait for the stream to complete or timeout
                long timeoutMs = config.effectiveReadTimeoutMs();
                boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                if (!completed && finished.compareAndSet(false, true)) {
                    ProviderFailure timeoutFailure = new ProviderFailure(ProviderFailureType.TIMEOUT, null, "Stream timed out", true);
                    onChunk.accept(new StreamChunk("error", timeoutFailure.message(), null, null, null, true, null, null, null, timeoutFailure));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (token.isCancellationRequested()) {
                emitCancelled(onChunk);
            }
        } catch (Exception e) {
            log.error("Langchain4j stream initiation error: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : "Stream error";
            ProviderFailure failure = new ProviderFailure(ProviderFailureType.NETWORK, null, msg, true);
            onChunk.accept(new StreamChunk("error", msg, null, null, null, true, null, null, null, failure));
        }
    }

    private static Map<String, Object> parseAiMessageResponse(Response<AiMessage> response) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (response == null || response.content() == null) {
            result.put("type", "text");
            result.put("content", "");
            return result;
        }

        AiMessage message = response.content();
        if (message.hasToolExecutionRequests()) {
            result.put("type", "tool_call");
            List<Map<String, Object>> calls = new ArrayList<>();
            for (int idx = 0; idx < message.toolExecutionRequests().size(); idx++) {
                ToolExecutionRequest req = message.toolExecutionRequests().get(idx);
                Map<String, Object> call = new HashMap<>();
                String effectiveId = req.id() != null && !req.id().isBlank()
                        ? req.id()
                        : "call_" + idx + "_" + Long.toHexString(System.nanoTime());
                call.put("id", effectiveId);
                call.put("type", "function");
                call.put("function", Map.of("name", req.name(), "arguments", req.arguments()));
                calls.add(call);
            }
            result.put("tool_calls", calls);
            result.put("content", message.text() != null ? message.text() : "");
        } else {
            result.put("type", "text");
            result.put("content", message.text() != null ? message.text() : "");
        }

        if (response.tokenUsage() != null) {
            result.put("usage", extractUsage(response.tokenUsage()));
        }
        return result;
    }

    private static Map<String, Object> extractUsage(TokenUsage usage) {
        if (usage == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new HashMap<>();
        if (usage.inputTokenCount() != null) {
            map.put("prompt_tokens", usage.inputTokenCount());
        }
        if (usage.outputTokenCount() != null) {
            map.put("completion_tokens", usage.outputTokenCount());
        }
        if (usage.totalTokenCount() != null) {
            map.put("total_tokens", usage.totalTokenCount());
        }
        return map;
    }

    private static Map<String, Object> cancelledResponse() {
        return Map.of("type", "cancelled", "content", "", "cancelled", true);
    }

    private static void emitCancelled(Consumer<StreamChunk> onChunk) {
        if (onChunk != null) {
            onChunk.accept(new StreamChunk("cancelled", "", null, null, null, true, null));
        }
    }
}
