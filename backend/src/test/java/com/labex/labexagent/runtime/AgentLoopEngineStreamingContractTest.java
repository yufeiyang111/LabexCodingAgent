package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineStreamingContractTest {

    private final String source = readSource();

    @Test
    void forwardsProviderTextAndThinkingDeltasWithoutArtificialDelay() {
        assertTrue(source.contains("case TEXT_DELTA ->"));
        assertTrue(source.contains("sse.sendTransient(\"FINAL_DELTA\""));
        assertTrue(source.contains("sse.sendTransient(\"THINK_DELTA\""));
        assertFalse(source.contains("Thread.sleep(28L)"));
        assertFalse(source.contains("chunkThought(deltaChunk)"));
        assertFalse(source.contains("this.streamFinal(sse, conv, ft"));
    }

    @Test
    void wrapsProviderStreamWithHardTimeout() {
        assertTrue(source.contains("PROVIDER_FIRST_EVENT_TIMEOUT_MS"));
        assertTrue(source.contains("streamFuture.get(PROVIDER_FIRST_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)"));
        assertTrue(source.contains("catch (TimeoutException e)"));
        assertTrue(source.contains("requestProviderCancellation(cancellationToken)"));
        assertTrue(source.contains("streamFuture.cancel(true)"));
        assertTrue(source.contains("????????"));
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

    private String readSource() {
        try {
            return Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}