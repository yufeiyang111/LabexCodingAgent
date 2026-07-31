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
    void usesAResumeScopedRunningTransitionKeyForEveryDurableContinuationEntry() {
        assertTrue(source.contains("AgentRunTransitionKey.forResumedRunUpdate"));
        assertTrue(source.contains("request.getSubmittedAt()"));
        assertTrue(source.contains("\"Recovered execution\",\n                        \"Execution resumed after lease takeover\",\n                        AgentRunTransitionKey.forResumedRunUpdate("));
    }

    @Test
    void providerRequestsUseDurableTranscriptAsTheOnlyInputSource() {
        assertTrue(source.contains("providerMessagesForBudget("));
        assertTrue(source.contains("requireTranscriptProjectionService().loadProviderMessages(taskId)"));
        assertTrue(source.contains("sysPrompt, providerMessages, tools, llmProvider"));
        assertFalse(source.contains("projectProviderMessages("));
        assertFalse(source.contains("projectForProvider(taskId, inMemoryMessages).messages()"));
        assertFalse(source.contains("return this.providerMessageProjector.project(inMemoryMessages);"));
        assertFalse(source.contains("return inMemoryMessages"));
        assertFalse(source.contains("List<Map<String, Object>> inMemoryMessages"));
        assertFalse(source.contains("? this.transcriptService.loadProjectableTranscript"));
    }

    @Test
    void consumesAnApprovedOfflineRetryWithoutAskingForTheSameCommandAgain() {
        assertTrue(source.contains("hasApprovedOfflineRetryGrant(ctx.getTaskId(), guardedCommand)"));
        assertTrue(source.contains("&& !approvedOfflineRetry"));
        assertTrue(source.contains("boolean networkRequested = this.networkRequested(args) || approvedOfflineRetry;"));
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
    void publishesTypedDurableInteractionEventsWithoutPseudoTerminalFrames() {
        assertTrue(source.contains("case \"permission\" -> \"PERMISSION_ASK\""));
        assertTrue(source.contains("case \"network\" -> \"NETWORK_ACCESS_ASK\""));
        assertTrue(source.contains("default -> \"USER_QUESTION\""));
        assertTrue(source.contains("\"TASK_PAUSED\""));
        assertTrue(source.contains("\"resumeAgentLoop\", true"));
        assertFalse(source.contains("this.streamFinal(sse, conv, this.buildStopFinal(waitingTitle"));
        assertFalse(source.contains("this.streamFinal(sse, conv, this.buildStopFinal(pause.title()"));
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
            return Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/" + fileName))
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
