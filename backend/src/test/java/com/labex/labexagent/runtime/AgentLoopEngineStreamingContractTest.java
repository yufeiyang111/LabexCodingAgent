package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineStreamingContractTest {

    private final String source = readSource("AgentLoopEngine.java") + readSource("AgentModelTurnExecutor.java");

    @Test
    void forwardsProviderTextAndThinkingDeltasWithoutArtificialDelay() {
        assertTrue(source.contains("case TEXT_DELTA ->"));
        assertTrue(source.contains("sse.sendTransient(\"FINAL_DELTA\""));
        assertTrue(source.contains("sse.sendTransient(\"THINK_DELTA\""));
        assertTrue(source.contains("THINK_SNAPSHOT"));
        assertFalse(source.contains("Thread.sleep(28L)"));
        assertFalse(source.contains("chunkThought(deltaChunk)"));
        assertFalse(source.contains("this.streamFinal(sse, conv, ft"));
    }

    @Test
    void usesAResumeScopedRunningTransitionKeyForDurableContinuations() {
        assertTrue(source.contains("AgentRunTransitionKey.forResumedRunUpdate"));
        assertTrue(source.contains("request.getSubmittedAt()"));
    }

    @Test
    void publishesUserQuestionsOnlyAfterTheDurableWaitingTransition() {
        assertTrue(source.contains("this.publishUserQuestion(sse, conv, res);"));
        int pause = source.indexOf("AgentInteractionPauser.Pause pause = this.interactionPauser.pause(");
        int publish = source.indexOf("this.publishUserQuestion(sse, conv, res);", pause);
        assertTrue(pause >= 0);
        assertTrue(publish > pause);
    }

    @Test
    void wrapsProviderStreamWithHardTimeout() {
        assertTrue(source.contains("PROVIDER_FIRST_EVENT_TIMEOUT_MS"));
        assertTrue(source.contains("future.get(timeoutMs, TimeUnit.MILLISECONDS)"));
        assertTrue(source.contains("catch (TimeoutException timeout)"));
        assertTrue(source.contains("cancellationRequester.accept(request.cancellationToken())"));
        assertTrue(source.contains("future.cancel(true)"));
        assertTrue(source.contains("模型服务响应超时"));
    }

    @Test
    void failsFastForModelTimeoutsAndCapsInitialContext() {
        assertTrue(source.contains("if (this.isModelTimeoutError(errMsg))"));
        assertTrue(source.contains("scheduleModelRetry("));
        assertTrue(source.contains("RETRY_SCHEDULED"));
        assertFalse(source.contains("Thread.sleep(delay)"));
        assertTrue(source.contains("message == null || isModelTimeoutError(message)"));
        assertTrue(source.contains("limitForContext(memoryContext, 16000)"));
        assertTrue(source.contains("limitForContext(sessionContext, 60000)"));
    }

    private String readSource(String fileName) {
        try {
            return Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/" + fileName));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
