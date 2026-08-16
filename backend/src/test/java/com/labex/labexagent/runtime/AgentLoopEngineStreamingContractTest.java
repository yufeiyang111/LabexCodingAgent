package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineStreamingContractTest {

    private final String source = readSource("AgentLoopEngine.java") + readSource("AgentModelTurnExecutor.java");

    @Test
    void givesTruncatedDurableToolOutputACallScopedReopenPath() {
        assertTrue(source.contains("compactToolResultForModel(tn, res, toolCallId)"));
        assertTrue(source.contains("read_tool_output"));
        assertTrue(source.contains("tool_call_id"));
    }

    @Test
    void quotesButDoesNotMutateValidatedToolCallIdsWhenBuildingTheModelReopenHint() {
        assertTrue(source.contains("tool_call_id=\\\""));
        assertTrue(source.contains("this.escapeJson(toolCallId)"));
        assertFalse(source.contains("toolCallId.trim()"));
    }

    @Test
    void forwardsProviderTextAndThinkingDeltasWithoutArtificialDelay() {
        assertTrue(source.contains("case TEXT_DELTA ->"));
        assertTrue(source.contains("request.eventSink().transientEvent(\"FINAL_CANDIDATE_DELTA\""));
        assertTrue(source.contains("request.eventSink().transientEvent(\"THINK_DELTA\""));
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
    void consumesOnlyThePreclaimedInteractionContinuationWithoutRebuildingItFromRequestIds() {
        assertTrue(source.contains("AgentRunInteraction preclaimedInteraction"));
        assertTrue(source.contains("resolvedInteractionToolResults(preclaimedInteraction, persistedMessages)"));
        assertTrue(source.contains("markDispatchClaimConsumed("));
        assertFalse(source.contains("findById(request.getResumeInteractionId())"));
        assertFalse(source.contains("runInteractionService.findById("));
    }

    @Test
    void providerRequestsUseDurableTranscriptAsTheOnlyInputSource() {
        assertTrue(source.contains("providerMessagesForBudget("));
        assertTrue(source.contains("requireTranscriptProjectionService().loadProviderMessages(taskId)"));
        assertTrue(source.contains("sysPrompt, providerMessages, tools, llmProvider")
                || source.contains("sysPrompt, modelTurnMessages, tools, llmProvider"));
        assertFalse(source.contains("projectProviderMessages("));
        assertFalse(source.contains("projectForProvider(taskId, inMemoryMessages).messages()"));
        assertFalse(source.contains("return this.providerMessageProjector.project(inMemoryMessages);"));
        assertFalse(source.contains("return inMemoryMessages"));
        assertFalse(source.contains("List<Map<String, Object>> inMemoryMessages"));
        assertFalse(source.contains("? this.transcriptService.loadProjectableTranscript"));
    }

    @Test
    void maxStepsSentinelIsDerivedReadOnlyAndNeverWrittenToTheTranscript() {
        assertTrue(source.contains("withMaxStepsSentinel(providerMessages)"));
        assertTrue(source.contains("Map.of(\"role\", \"assistant\", \"content\", MAX_STEPS_SENTINEL)"));
        assertTrue(source.contains("List.copyOf(result)"));
        assertFalse(source.contains("this.appendProviderMessage(task.getTaskId(), transcriptEpoch, Map.of(\"role\", \"assistant\", \"content\", MAX_STEPS_SENTINEL"));
        assertFalse(source.contains("MAX_STEPS_SENTINEL\"));"));
    }

    @Test
    void networkCommandsExecuteDirectlyWithoutOfflineRetryGrants() {
        // 网络访问默认开启：命令策略路径不再计算 offline-retry grant，也不存在离线优先执行。
        assertFalse(source.contains("hasApprovedOfflineRetryGrant("));
        assertFalse(source.contains("boolean networkRequested = !opencodeShell && (this.networkRequested(args) || approvedOfflineRetry);"));
        assertFalse(source.contains("approvedOfflineRetry"));
        assertTrue(source.contains("// 网络访问默认开启：不再为网络命令创建一次性审批"));
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
    void doesNotNarrateWaitingInteractionAsFailureBeforePausing() {
        // opencode 语义：等待用户输入时没有工具结果，OBSERVE/结果叙述必须发生在
        // isInteractionRequired 暂停分支（含 return）之后，不能把暂停叙述成失败。
        String marker = "if (res.isInteractionRequired()) {";
        int firstPauseBranch = source.indexOf(marker);
        assertTrue(firstPauseBranch >= 0);
        int secondPauseBranch = source.indexOf(marker, firstPauseBranch + marker.length());
        assertTrue(secondPauseBranch > firstPauseBranch);
        int nativeObserve = source.indexOf("this.sendObserve(sse, conv, i, tn, res, task.getTaskId(), toolCallId);");
        int recoveredObserve = source.indexOf("this.sendObserve(sse, conv, i, invTool, res, task.getTaskId(), recoveredToolCallId);");
        assertTrue(nativeObserve > firstPauseBranch);
        assertTrue(recoveredObserve > secondPauseBranch);
    }

    @Test
    void emitsDurableWorkspaceChangedForFileToolsSoTheFrontendTreeRefreshes() {
        // 文件类工具（write_file/edit_file/apply_patch）返回 diff/pendingChangeId 时，
        // 必须补发 WORKSPACE_CHANGED，否则前端文件树没有响应式刷新触发源。
        assertTrue(source.contains("this.sendEvent(sse, conv, \"WORKSPACE_CHANGED\", workspace, workspaceKey);"));
        assertTrue(source.contains("if (r.getDiff() != null || r.getPendingChangeId() != null) {"));
        assertTrue(source.contains("workspace.put(\"workspaceChangeId\", \"tool:\" + (toolCallId == null ? \"unknown\" : toolCallId));"));
        assertTrue(source.contains("workspace.put(\"workspaceIdentity\", r.getWorkspaceIdentity());"));
        assertTrue(source.contains("workspace.put(\"workspaceMutation\", workspaceMutation);"));
        assertTrue(source.contains("workspace.put(\"workspaceVerification\", workspaceVerification);"));
        assertTrue(source.contains("String workspaceKey = toolCallId == null || toolCallId.isBlank() ? \"\" : \"tool-workspace-changed:\" + toolCallId;"));
        int observe = source.indexOf("this.sendEvent(sse, conv, \"OBSERVE\", o);");
        int workspaceChanged = source.indexOf("this.sendEvent(sse, conv, \"WORKSPACE_CHANGED\", workspace, workspaceKey);");
        assertTrue(observe >= 0);
        assertTrue(workspaceChanged > observe);
    }

    @Test
    void sendsTheSameStructuredFailureAndExecutionEvidenceInLiveObservations() {
        // OBSERVE 是实时投影，不能因 transport success=true 而省略实际执行失败事实；
        // 前端可在刷新前后使用同一份 durable metadata 渲染结果。
        assertTrue(source.contains("Map<String, Object> durableMetadata = r.durableResultMetadata();"));
        assertTrue(source.contains("Object execution = durableMetadata.get(\"execution\");"));
        assertTrue(source.contains("o.put(\"execution\", execution);"));
        assertTrue(source.contains("Object failureClass = durableMetadata.get(\"failureClass\");"));
        assertTrue(source.contains("o.put(\"failureClass\", failureClass);"));
    }

    @Test
    void separatesActualExecutionFailureFromModelTransportCompletion() {
        // 非零 shell exit 仍是可回填给模型的 completed transport，但工程状态、失败熔断与恢复提示
        // 必须使用实际 execution outcome，不能把它当作一次成功验证。
        assertTrue(source.contains("boolean successfulExecutionOutcome = result.isSuccessfulExecutionOutcome();"));
        assertTrue(source.contains("!successfulExecutionOutcome && !result.isApprovalRequired()"));
        assertTrue(source.contains("result == null || result.isSuccessfulExecutionOutcome() || result.isApprovalRequired()"));
        assertTrue(source.contains("o.put(\"success\", r.isSuccess());"));
    }
    @Test
    void preservesStructuredToolEvidenceWhenLegacyTurnsReachTerminalPartStates() {
        // legacy 对话仍可能继续执行；不能在这里退化为 String detail，
        // 否则 workspace identity、mutation 和 verification 都会从 durable Part 中丢失。
        assertTrue(source.contains("this.toolCallJournalService.completed(executionFence, taskId, toolCallId, toolName, arguments, iteration, result);"));
        assertTrue(source.contains("this.toolCallJournalService.interrupted(executionFence, taskId, toolCallId, toolName, arguments, iteration, result);"));
        assertTrue(source.contains("this.toolCallJournalService.blocked(executionFence, taskId, toolCallId, toolName, arguments, iteration, result);"));
        assertTrue(source.contains("this.toolCallJournalService.failed(executionFence, taskId, toolCallId, toolName, arguments, iteration, result);"));
    }

    @Test
    void projectsResolvedInteractionAsCompletedToolResultOnResume() {
        assertTrue(source.contains("projectResolvedInteraction(sse, conv, task, executionFence, preclaimedInteraction, visibleLanguage)"));
        assertTrue(source.contains("toolCallJournalService.completed(executionFence, task.getTaskId(), toolCallId, \"question\""));
        assertTrue(source.contains("toolCallJournalService.interrupted(executionFence, task.getTaskId(), toolCallId, \"question\""));
        assertTrue(source.contains("sendObservePayload(sse, conv, task.getTaskId(), toolCallId, resultText, true)"));
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
    void textToolFallbackUsesStrictTypedParsingSchemaGateAndBoundedRecovery() {
        assertTrue(source.contains("ToolCallExtractor.extract(content)"));
        assertTrue(source.contains("new TextToolCallStreamBoundary(projectVisible)"));
        assertTrue(source.contains("resolveRecovered(ctx, invTool, parsedArgs, visibleLanguage)"));
        assertTrue(source.contains("MAX_TEXT_TOOL_CALL_RECOVERY_FAILURES = 2"));
        assertTrue(source.contains("text_tool_call_recovery_exhausted"));
        assertFalse(source.contains("ToolCallExtractor.extractToolName"));
        assertFalse(source.contains("ToolCallExtractor.extractToolArgs"));
        assertFalse(source.contains("extracted tool={} args={}"));
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
        assertTrue(source.contains("sendPersistedEvent(sse, conv, retry.event())"));
        assertFalse(source.contains("sendEvent(sse, conv, \"RETRY_SCHEDULED\""));
        assertFalse(source.contains("Thread.sleep(delay)"));
        assertTrue(source.contains("message == null || isModelTimeoutError(message)"));
        assertTrue(source.contains("limitForContext(projectRules, 10_000)"));
        assertTrue(source.contains("limitForContext(leanMemory, 2_000)"));
        assertTrue(source.contains("limitForContext(recentRunLog, 12_000)"));
        assertTrue(source.contains("limitForContext(checkpoint, 12_000)"));
        assertFalse(source.contains("limitForContext(sessionContext, 60000)"));
        assertFalse(source.contains("limitForContext(globalSkills, 16000)"));
        assertFalse(source.contains("limitForContext(mcpContext, 12000)"));
    }

    @Test
    void projectsTransactionallyPersistedPlanEventBeforeLaterToolLifecycleEvents() {
        int delegate = source.indexOf("ToolResult result = this.toolTurnExecutor.execute(t, ctx, args, name, toolCallId);");
        int projection = source.indexOf("this.projectPersistedPlanUpdate(sse, ctx);", delegate);
        int laterToolPhase = source.indexOf("phase = \"post_edit_hook\";", delegate);

        assertTrue(delegate >= 0);
        assertTrue(projection > delegate);
        assertTrue(projection < laterToolPhase);
        assertTrue(source.indexOf("this.projectPersistedPlanUpdate(sse, ctx);", projection + 1) < 0);
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
