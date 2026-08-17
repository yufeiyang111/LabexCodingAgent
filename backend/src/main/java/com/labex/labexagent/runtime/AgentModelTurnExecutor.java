package com.labex.labexagent.runtime;

import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.ProviderCapabilities;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 执行单次模型流式调用，只负责 Provider 能力协商、流拼装、超时和原生工具调用身份。
 * 任务状态和工具执行仍由 AgentLoopEngine 及生命周期服务负责。
 */
@org.springframework.stereotype.Service
public class AgentModelTurnExecutor {
    private static final Logger log = LoggerFactory.getLogger(AgentModelTurnExecutor.class);
    private static final ExecutorService DEFAULT_EXECUTOR = new ThreadPoolExecutor(
            0, 16, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            new ProviderStreamThreadFactory(), new ThreadPoolExecutor.AbortPolicy());

    private final ExecutorService executorService;
    private final long timeoutMs;

    /** Spring 运行时从集中化配置读取单轮总 watchdog；最小装配上下文缺少 properties 时安全回退默认值。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AgentModelTurnExecutor(ObjectProvider<AgentModelTurnProperties> propertiesProvider) {
        this(DEFAULT_EXECUTOR, configuredTimeout(propertiesProvider));
    }

    private static long configuredTimeout(ObjectProvider<AgentModelTurnProperties> propertiesProvider) {
        AgentModelTurnProperties properties = propertiesProvider == null ? null : propertiesProvider.getIfAvailable();
        return properties == null ? AgentModelTurnProperties.DEFAULT_TOTAL_TIMEOUT_MS : properties.getTotalTimeoutMs();
    }

    public AgentModelTurnExecutor() {
        this(DEFAULT_EXECUTOR, AgentModelTurnProperties.DEFAULT_TOTAL_TIMEOUT_MS);
    }

    AgentModelTurnExecutor(long timeoutMs) {
        this(DEFAULT_EXECUTOR, timeoutMs);
    }

    AgentModelTurnExecutor(ExecutorService executorService, long timeoutMs) {
        this.executorService = executorService;
        this.timeoutMs = Math.max(0L, timeoutMs);
    }
    ModelTurnResult execute(ModelTurnRequest request) throws Exception {
        ProviderCapabilities capabilities = request.provider().capabilities();
        if (!capabilities.streaming()) {
            return ModelTurnResult.error("Selected provider does not support streaming", "", "", Map.of());
        }
        if (request.tools() != null && !request.tools().isEmpty() && !capabilities.toolCalling()) {
            return ModelTurnResult.error("Selected provider does not support tool calling", "", "", Map.of());
        }

        StringBuilder content = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Map<Integer, LlmProvider.StreamChunk> toolCalls = new LinkedHashMap<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<Map<String, Object>> usage = new AtomicReference<>(Map.of());
        boolean[] cancelled = {false};
        boolean[] terminalEvent = {false};
        boolean[] thinkingStarted = {false};
        int[] lastThinkingCheckpointLength = {0};
        long[] lastThinkingCheckpointAt = {System.nanoTime()};
        String thinkingMessageId = "think-" + request.iteration() + "-" + UUID.randomUUID();

        Consumer<String> publishThinking = delta -> {
            if (delta == null || delta.isEmpty()) return;
            try {
                if (!thinkingStarted[0]) {
                    request.eventSink().durable("THINK_START", Map.of(
                            "messageId", thinkingMessageId,
                            "iteration", request.iteration(),
                            "summary", localText(request.visibleLanguage(), "\u5206\u6790\u95ee\u9898", "Analyzing problem"),
                            "taskId", request.taskId()));
                    thinkingStarted[0] = true;
                }
                thinking.append(delta);
                request.eventSink().transientEvent("THINK_DELTA", Map.of(
                        "messageId", thinkingMessageId,
                        "delta", delta,
                        "taskId", request.taskId()));
                long now = System.nanoTime();
                if (thinking.length() - lastThinkingCheckpointLength[0] >= 1000
                        || now - lastThinkingCheckpointAt[0] >= TimeUnit.SECONDS.toNanos(1)) {
                    request.eventSink().durable("THINK_SNAPSHOT", Map.of(
                            "messageId", thinkingMessageId,
                            "iteration", request.iteration(),
                            "content", thinking.toString(),
                            "streaming", true,
                            "taskId", request.taskId()));
                    lastThinkingCheckpointLength[0] = thinking.length();
                    lastThinkingCheckpointAt[0] = now;
                }
            } catch (Exception eventFailure) {
                throw new IllegalStateException("Failed to project reasoning delta", eventFailure);
            }
        };
        Consumer<String> projectVisible = delta -> {
            if (delta == null || delta.isEmpty()) return;
            try {
                request.eventSink().transientEvent("FINAL_CANDIDATE_DELTA", Map.of(
                        "delta", delta,
                        "taskId", request.taskId()));
            } catch (Exception eventFailure) {
                throw new IllegalStateException("Failed to project visible delta", eventFailure);
            }
        };
        TextToolCallStreamBoundary textToolCallStreamBoundary =
                new TextToolCallStreamBoundary(projectVisible);
        Consumer<String> collectVisible = delta -> {
            if (delta == null || delta.isEmpty()) return;
            content.append(delta);
            textToolCallStreamBoundary.push(delta);
        };
        InternalReasoningBoundary.TagStreamFilter dedicatedReasoningFilter =
                new InternalReasoningBoundary.TagStreamFilter(publishThinking);
        InternalReasoningBoundary.VisibleStreamFilter visibleContentFilter =
                new InternalReasoningBoundary.VisibleStreamFilter(publishThinking, collectVisible);

        TurnCancellationToken turnCancellationToken = new TurnCancellationToken(request.cancellationToken());
        Future<?> future = executorService.submit(() -> request.provider().chatStream(
                request.systemPrompt(), request.messages(), request.tools(), request.config(),
                turnCancellationToken, chunk -> {
                    if (turnCancellationToken.isCancellationRequested()) {
                        return;
                    }
                    try {
                        switch (chunk.eventType()) {
                            case CANCELLED -> {
                                cancelled[0] = true;
                                terminalEvent[0] = true;
                            }
                            case THINKING_DELTA -> dedicatedReasoningFilter.push(chunk.content());
                            case TEXT_DELTA -> visibleContentFilter.push(chunk.content());
                            case TOOL_ARGUMENTS_DELTA, TOOL_CALL -> {
                                int index = chunk.toolCallIndex() == null ? 0 : chunk.toolCallIndex();
                                toolCalls.put(index, chunk);
                                if (chunk.usage() != null) usage.set(chunk.usage());
                            }
                            case ERROR -> {
                                terminalEvent[0] = true;
                                String failureMessage = chunk.content();
                                if ((failureMessage == null || failureMessage.isBlank()) && chunk.failure() != null) {
                                    failureMessage = chunk.failure().message();
                                }
                                error.set(failureMessage == null || failureMessage.isBlank()
                                        ? "Provider returned an unspecified stream error" : failureMessage);
                            }
                            case USAGE -> {
                                if (chunk.usage() != null) usage.set(chunk.usage());
                            }
                            case DONE -> {
                                dedicatedReasoningFilter.flush();
                                visibleContentFilter.flush();
                                textToolCallStreamBoundary.finish();
                                terminalEvent[0] = true;
                                if (chunk.usage() != null) usage.set(chunk.usage());
                            }
                            case UNKNOWN -> log.debug("Ignoring unknown provider event type={}", chunk.type());
                        }
                    } catch (Exception callbackFailure) {
                        log.warn("Model stream callback failed: {}", callbackFailure.getMessage());
                    }
                }));

        FailureReason failureReason = FailureReason.NONE;
        try {
            if (timeoutMs > 0L) {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                future.get();
            }
        } catch (TimeoutException timeout) {
            turnCancellationToken.requestTimeoutCancellation();
            future.cancel(true);
            failureReason = FailureReason.MODEL_TIMEOUT;
            error.set(localText(request.visibleLanguage(),
                    "模型服务响应超时，已自动停止本次请求。请检查模型服务、网络或换一个模型后重试。",
                    "Model service timed out, so this request was stopped. Check the provider, network, or try another model."));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            turnCancellationToken.requestTransportCancellation();
            future.cancel(true);
            if (request.cancellationToken().isCancellationRequested()) {
                cancelled[0] = true;
            } else {
                error.set(localText(request.visibleLanguage(),
                        "模型流式调用被运行线程中断。",
                        "Model stream execution was interrupted."));
            }
        } catch (Exception failure) {
            turnCancellationToken.requestTransportCancellation();
            future.cancel(true);
            if (request.cancellationToken().isCancellationRequested()) {
                cancelled[0] = true;
            } else {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                String failureMessage = cause.getMessage();
                error.set(failureMessage == null || failureMessage.isBlank()
                        ? "Provider stream failed without an error message." : failureMessage);
            }
        } finally {
            turnCancellationToken.close();
        }
        if (error.get() == null && !cancelled[0] && !terminalEvent[0]) {
            error.set("Provider stream ended before terminal event; response may be truncated.");
        }

        if (thinkingStarted[0]) {
            Map<String, Object> completedThinking = new LinkedHashMap<>();
            completedThinking.put("messageId", thinkingMessageId);
            completedThinking.put("iteration", request.iteration());
            completedThinking.put("summary", localText(request.visibleLanguage(), "思考完成", "Thinking complete"));
            completedThinking.put("content", thinking.toString());
            completedThinking.put("streaming", false);
            completedThinking.put("taskId", request.taskId());
            request.eventSink().durable("THINK", completedThinking);
        }

        Map<String, Object> usageValue = usage.get() == null ? Map.of() : usage.get();
        if (error.get() != null) {
            return ModelTurnResult.error(failureReason, error.get(), content.toString(), thinking.toString(), usageValue);
        }
        if (cancelled[0] || request.cancellationToken().isCancellationRequested()) {
            return ModelTurnResult.cancelled(content.toString(), thinking.toString(), usageValue);
        }
        if (!toolCalls.isEmpty()) {
            List<NativeToolCall> completedToolCalls = toolCalls.values().stream()
                    .map(chunk -> new NativeToolCall(
                            chunk.toolName(),
                            chunk.toolArgs() == null ? "" : chunk.toolArgs(),
                            chunk.toolCallId(),
                            chunk.toolCallIndex() == null ? 0 : chunk.toolCallIndex()))
                    .sorted(Comparator.comparingInt(NativeToolCall::toolCallIndex))
                    .toList();
            return ModelTurnResult.toolCalls(content.toString(), thinking.toString(), completedToolCalls, usageValue);
        }
        String finalContent = content.toString().isBlank() ? thinking.toString() : content.toString();
        return ModelTurnResult.text(finalContent, thinking.toString(), usageValue);
    }

    /**
     * 单轮 provider transport 的取消边界：parent 只承载用户/生命周期取消，
     * watchdog 与执行线程中断只能标记本轮，不能反向污染 ActiveRun。
     */
    private static final class TurnCancellationToken implements CancellationToken, AutoCloseable {
        private final CancellationToken parent;
        private final AtomicBoolean transportCancellationRequested = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final Registration parentRegistration;

        private TurnCancellationToken(CancellationToken parent) {
            this.parent = parent == null ? CancellationToken.none() : parent;
            this.parentRegistration = this.parent.onCancellation(this::notifyCancellation);
        }

        @Override
        public boolean isCancellationRequested() {
            return transportCancellationRequested.get() || parent.isCancellationRequested();
        }

        @Override
        public Registration onCancellation(Runnable listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener is required");
            }
            AtomicBoolean invoked = new AtomicBoolean();
            Runnable once = () -> {
                if (invoked.compareAndSet(false, true)) {
                    listener.run();
                }
            };
            listeners.add(once);
            if (isCancellationRequested()) {
                once.run();
            }
            return () -> listeners.remove(once);
        }

        void requestTimeoutCancellation() {
            requestTransportCancellation();
        }

        void requestTransportCancellation() {
            if (transportCancellationRequested.compareAndSet(false, true)) {
                notifyCancellation();
            }
        }

        private void notifyCancellation() {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                    // 单个 transport listener 失败不能阻止其它 HTTP/SSE 请求停下。
                }
            }
        }

        @Override
        public void close() {
            parentRegistration.close();
            listeners.clear();
        }
    }

    private static String localText(String visibleLanguage, String zh, String en) {
        return "zh".equalsIgnoreCase(visibleLanguage) ? zh : en;
    }

    enum ResultType { TEXT, TOOL_CALL, ERROR, CANCELLED }

    enum FailureReason {
        NONE(""),
        MODEL_TIMEOUT("model_timeout"),
        PROVIDER_ERROR("provider_error");

        private final String code;

        FailureReason(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    record NativeToolCall(String toolName, String toolArguments, String toolCallId, int toolCallIndex) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("tool", toolName == null ? "" : toolName);
            value.put("arguments", toolArguments == null ? "" : toolArguments);
            value.put("toolCallId", toolCallId);
            value.put("toolCallIndex", toolCallIndex);
            return value;
        }
    }

    record ModelTurnResult(ResultType type, String content, String thinking, String message,
                           List<NativeToolCall> toolCalls, Map<String, Object> usage,
                           FailureReason failureReason) {
        ModelTurnResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            failureReason = failureReason == null ? FailureReason.NONE : failureReason;
        }

        static ModelTurnResult text(String content, String thinking, Map<String, Object> usage) {
            return new ModelTurnResult(ResultType.TEXT, content, thinking, "", List.of(), usage, FailureReason.NONE);
        }

        static ModelTurnResult toolCalls(String content, String thinking, List<NativeToolCall> toolCalls,
                                         Map<String, Object> usage) {
            return new ModelTurnResult(ResultType.TOOL_CALL, content, thinking, "", toolCalls, usage, FailureReason.NONE);
        }

        static ModelTurnResult error(String message, String content, String thinking, Map<String, Object> usage) {
            return error(FailureReason.PROVIDER_ERROR, message, content, thinking, usage);
        }

        static ModelTurnResult error(FailureReason failureReason, String message, String content,
                                     String thinking, Map<String, Object> usage) {
            FailureReason effectiveReason = failureReason == null || failureReason == FailureReason.NONE
                    ? FailureReason.PROVIDER_ERROR : failureReason;
            return new ModelTurnResult(ResultType.ERROR, content, thinking, message, List.of(), usage, effectiveReason);
        }

        static ModelTurnResult cancelled(String content, String thinking, Map<String, Object> usage) {
            return new ModelTurnResult(ResultType.CANCELLED, content, thinking, "", List.of(), usage, FailureReason.NONE);
        }
        String reasonCode() {
            return failureReason.code();
        }

        String toolName() {
            return firstToolCall() == null ? "" : firstToolCall().toolName();
        }

        String toolArguments() {
            return firstToolCall() == null ? "" : firstToolCall().toolArguments();
        }

        String toolCallId() {
            return firstToolCall() == null ? null : firstToolCall().toolCallId();
        }

        Integer toolCallIndex() {
            return firstToolCall() == null ? null : firstToolCall().toolCallIndex();
        }

        private NativeToolCall firstToolCall() {
            return toolCalls.isEmpty() ? null : toolCalls.get(0);
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("type", switch (type) {
                case TEXT -> "text";
                case TOOL_CALL -> "tool_call";
                case ERROR -> "error";
                case CANCELLED -> "cancelled";
            });
            result.put("content", content == null ? "" : content);
            if (thinking != null && !thinking.isEmpty()) result.put("thinking", thinking);
            if (type == ResultType.ERROR) {
                result.put("message", message == null ? "" : message);
                result.put("reasonCode", reasonCode());
            }
            if (type == ResultType.TOOL_CALL && !toolCalls.isEmpty()) {
                NativeToolCall first = toolCalls.get(0);
                result.put("tool", first.toolName());
                result.put("arguments", first.toolArguments());
                result.put("toolCallId", first.toolCallId());
                result.put("toolCallIndex", first.toolCallIndex());
                result.put("toolCalls", toolCalls.stream().map(NativeToolCall::toMap).toList());
            }
            if (usage != null && !usage.isEmpty()) result.put("usage", usage);
            return result;
        }
    }

    record ModelTurnRequest(String systemPrompt, List<Map<String, Object>> messages,
                            List<Map<String, Object>> tools, LlmProvider provider,
                            LlmProvider.LlmConfig config, int iteration, Long taskId,
                            String visibleLanguage, CancellationToken cancellationToken,
                            EventSink eventSink) {
        ModelTurnRequest {
            cancellationToken = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        }

        ModelTurnRequest withCancellationToken(CancellationToken replacement) {
            return new ModelTurnRequest(systemPrompt, messages, tools, provider, config, iteration, taskId,
                    visibleLanguage, replacement, eventSink);
        }
    }

    private static final class ProviderStreamThreadFactory implements ThreadFactory {
        private int index = 1;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "labex-provider-stream-" + index++);
            thread.setDaemon(true);
            return thread;
        }
    }

    interface EventSink {
        void durable(String type, Object data) throws Exception;
        void transientEvent(String type, Object data) throws Exception;
    }
}
