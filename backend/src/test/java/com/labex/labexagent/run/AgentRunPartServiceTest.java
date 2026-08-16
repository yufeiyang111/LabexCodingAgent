package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunExecutionLeaseService.StaleExecutionFenceException;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRunPartServiceTest {

    @Test
    void createsAStableToolPartWithTaskOwnershipAndStructuredInput() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentTask task = task();
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task);
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(91L);
            return 1;
        });

        AgentRunPart result = new AgentRunPartService(parts, tasks, messageService())
                .upsertToolCall(7L, "call-1", "running", "run_tests",
                        Map.of("strategy", "test"), 3, "");

        assertThat(result.getPartId()).isEqualTo(91L);
        assertThat(result.getPartKey()).isEqualTo("tool:call-1");
        assertThat(result.getPartType()).isEqualTo("tool");
        assertThat(result.getStatus()).isEqualTo("running");
        assertThat(result.getConversationId()).isEqualTo("conversation-7");
        assertThat(result.getInputJson()).contains("strategy").contains("test");
        assertThat(result.getMetadata()).contains("executionEpoch").contains("4");
        verify(parts).insert(any(AgentRunPart.class));
    }

    @Test
    void persistsStructuredWorkspaceIdentityAlongsideTheToolPart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(91L);
            return 1;
        });
        Map<String, Object> workspaceIdentity = Map.of(
                "operationFingerprint", "a".repeat(64),
                "workingDirectory", "src/service",
                "relativePaths", List.of("src/service/Task.java"));

        AgentRunPart result = new AgentRunPartService(parts, tasks, messageService())
                .upsertToolCall(7L, "call-1", "completed", "shell",
                        Map.of("command", "npm test"), 3, "exit=0",
                        Map.of("workspaceIdentity", workspaceIdentity,
                                "execution", Map.of("status", "succeeded", "exitCode", 0)));

        assertThat(result.getMetadata())
                .contains("workspaceIdentity")
                .contains("operationFingerprint")
                .contains("src/service/Task.java")
                .contains("exitCode");
    }

    @Test
    void persistsToolCallDetailWithoutTruncation() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(91L);
            return 1;
        });

        String oversized = "z".repeat(50_000);
        AgentRunPart result = new AgentRunPartService(parts, tasks, messageService())
                .upsertToolCall(7L, "call-1", "completed", "run_tests",
                        Map.of("strategy", "test"), 3, oversized);

        assertThat(result.getOutputText())
                .hasSize(50_000)
                .doesNotContain("...truncated...");
    }

    @Test
    void historyProjectionTruncatesOversizedToolOutputWithMarkerButRecoveryPathStaysFull() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart stored = new AgentRunPart();
        stored.setPartId(1L);
        stored.setTaskId(7L);
        stored.setPartType("tool");
        stored.setToolName("run_tests");
        stored.setToolCallId("call-1");
        stored.setInputJson("{\"strategy\":\"test\"}");
        stored.setOutputText("z".repeat(10_000));
        stored.setSequenceNumber(1L);
        stored.setStatus("completed");
        when(parts.selectList(any())).thenReturn(List.of(stored));

        AgentRunPartService service = new AgentRunPartService(
                parts, mock(AgentTaskMapper.class), messageService());

        Map<Long, List<Map<String, Object>>> truncated =
                service.publicHistoryByTaskIds(List.of(7L), 4_000);
        assertThat(truncated.get(7L)).hasSize(1);
        assertThat(truncated.get(7L).get(0))
                .containsEntry("outputTruncated", true)
                .containsEntry("outputLength", 10_000);
        assertThat((String) truncated.get(7L).get(0).get("output")).hasSize(4_000);

        Map<Long, List<Map<String, Object>>> full = service.publicHistoryByTaskIds(List.of(7L));
        assertThat(full.get(7L).get(0)).doesNotContainKey("outputTruncated");
        assertThat((String) full.get(7L).get(0).get("output")).hasSize(10_000);
    }
    @Test
    void attachesTheToolPartToItsStableAssistantTurnMessage() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(41L);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.upsertAssistantTurn(7L, 3, "completed")).thenReturn(message);

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .upsertToolCall(7L, "call-1", "completed", "run_tests",
                        Map.of("strategy", "test"), 3, "passed");

        assertThat(result.getMessageId()).isEqualTo(41L);
    }

    @Test
    void updatesTheSamePartWhenTheToolLifecycleAdvances() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart existing = new AgentRunPart();
        existing.setPartId(91L);
        existing.setPartKey("tool:call-1");
        when(parts.selectOne(any())).thenReturn(existing);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart result = new AgentRunPartService(parts, tasks, messageService())
                .upsertToolCall(7L, "call-1", "completed", "run_tests",
                        Map.of("strategy", "test"), 3, "passed");

        assertThat(result).isSameAs(existing);
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getOutputText()).isEqualTo("passed");
        verify(parts).updateById(existing);
    }

    @Test
    void synchronizesWaitingStateToTheProviderToolCallPart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart providerCall = new AgentRunPart();
        providerCall.setPartId(92L);
        providerCall.setPartKey("provider:0:tool-call:2:0:call-1");
        providerCall.setPartType("tool_call");
        providerCall.setToolCallId("call-1");
        providerCall.setInputJson("{\"id\":\"call-1\",\"type\":\"function\"}");
        when(parts.selectList(any())).thenReturn(List.of(providerCall));
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        new AgentRunPartService(parts, tasks, messageService())
                .upsertToolCall(7L, "call-1", "waiting_approval", "read_file",
                        Map.of("path", ".env"), 2, "Waiting for user approval.");

        assertThat(providerCall.getStatus()).isEqualTo("waiting_approval");
        assertThat(providerCall.getInputJson()).contains("call-1");
        assertThat(providerCall.getOutputText()).isEqualTo("Waiting for user approval.");
        verify(parts).updateById(providerCall);
    }

    @Test
    void resolvesTheCompatibilityToolPartWithoutMutatingTheToolResult() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart providerCall = new AgentRunPart();
        providerCall.setPartId(90L);
        providerCall.setPartType("tool_call");
        providerCall.setToolCallId("call-1");
        AgentRunPart toolResult = new AgentRunPart();
        toolResult.setPartId(91L);
        toolResult.setPartType("tool_result");
        toolResult.setToolCallId("call-1");
        toolResult.setStatus("completed");
        AgentRunPart compatibilityTool = new AgentRunPart();
        compatibilityTool.setPartId(92L);
        compatibilityTool.setPartType("tool");
        compatibilityTool.setToolCallId("call-1");
        compatibilityTool.setSequenceNumber(3L);
        AtomicBoolean providerStateSynchronized = new AtomicBoolean();
        when(parts.selectList(any())).thenAnswer(invocation -> {
            providerStateSynchronized.set(true);
            return List.of(providerCall);
        });
        when(parts.selectOne(any())).thenAnswer(invocation ->
                providerStateSynchronized.get() ? compatibilityTool : toolResult);

        AgentRunPart result = new AgentRunPartService(parts, tasks, messageService())
                .resolveExistingToolCall(7L, "call-1", "interrupted", "status=interrupted");

        assertThat(result).isSameAs(compatibilityTool);
        assertThat(compatibilityTool.getStatus()).isEqualTo("interrupted");
        assertThat(providerCall.getStatus()).isEqualTo("interrupted");
        assertThat(toolResult.getStatus()).isEqualTo("completed");
    }

    @Test
    void externallyResolvedToolResultMergesStructuredEvidenceWithoutReplacingTheOriginalEpoch() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunPart providerCall = new AgentRunPart();
        providerCall.setPartId(90L);
        providerCall.setPartType("tool_call");
        providerCall.setToolCallId("call-1");
        AgentRunPart compatibilityTool = new AgentRunPart();
        compatibilityTool.setPartId(92L);
        compatibilityTool.setPartType("tool");
        compatibilityTool.setToolCallId("call-1");
        compatibilityTool.setToolName("shell");
        compatibilityTool.setStatus("waiting_approval");
        compatibilityTool.setSequenceNumber(3L);
        compatibilityTool.setMetadata("{\"executionEpoch\":4,\"sequence\":3,\"partType\":\"tool\",\"status\":\"waiting_approval\",\"existing\":\"preserved\"}");
        when(parts.selectList(any())).thenReturn(List.of(providerCall));
        when(parts.selectOne(any())).thenReturn(compatibilityTool);

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .resolveExistingToolCall(7L, "call-1", "completed", "status=completed\nexit=0",
                        Map.of(
                                "workspaceIdentity", Map.of("workingDirectory", ".", "relativePaths", List.of("skills/SKILL.md")),
                                "workspaceMutation", Map.of("state", "applied", "changeIds", List.of("change-1")),
                                "workspaceVerification", Map.of("state", "verified", "targets", List.of())));

        assertThat(result).isSameAs(compatibilityTool);
        assertThat(compatibilityTool.getMetadata())
                .contains("existing")
                .contains("preserved")
                .contains("workspaceIdentity")
                .contains("workspaceMutation")
                .contains("workspaceVerification")
                .contains("\"executionEpoch\":4")
                .contains("\"status\":\"completed\"");
        verify(messages).upsertAssistantTurn(7L, 3L, "completed");
    }

    @Test
    void projectsCompatibilityToolCallsFromDurableToolPartsOnly() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart reasoning = new AgentRunPart();
        reasoning.setPartId(90L);
        reasoning.setPartKey("reasoning:1");
        reasoning.setPartType("reasoning");
        reasoning.setStatus("completed");

        AgentRunPart tool = new AgentRunPart();
        tool.setPartId(91L);
        tool.setPartKey("tool:call-1");
        tool.setPartType("tool");
        tool.setToolCallId("call-1");
        tool.setToolName("run_tests");
        tool.setInputJson("{\"command\":\"mvn test\"}");
        tool.setOutputText("Tests passed");
        tool.setStatus("completed");
        tool.setSequenceNumber(3L);
        when(parts.selectList(any())).thenReturn(List.of(reasoning, tool));

        List<Map<String, Object>> projection = new AgentRunPartService(parts, tasks, messageService())
                .publicToolCalls(7L);

        assertThat(projection).containsExactly(Map.of(
                "partId", 91L,
                "partKey", "tool:call-1",
                "toolCallId", "call-1",
                "tool", "run_tests",
                "arguments", Map.of("command", "mvn test"),
                "status", "completed",
                "iteration", 3L,
                "detail", "Tests passed"));
    }

    @Test
    void fencedToolCallRejectsStaleOwnerBeforeWritingPartOrMessage() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunPartService service = new AgentRunPartService(parts, tasks, messages);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.upsertToolCall(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "call-1", "running", "run_tests", Map.of("strategy", "test"), 3, ""));

        assertEquals(StaleExecutionFenceException.Reason.STALE_OWNER, error.reason());
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
        verify(messages, never()).upsertAssistantTurn(anyLong(), anyLong(), anyString());
    }

    @Test
    void fencedToolCallRejectsStaleEpochBeforeWritingPartOrMessage() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunPartService service = new AgentRunPartService(parts, tasks, messages);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.upsertToolCall(new ExecutionFence(7L, "instance-a", 3L),
                        7L, "call-1", "running", "run_tests", Map.of("strategy", "test"), 3, ""));

        assertEquals(StaleExecutionFenceException.Reason.STALE_EPOCH, error.reason());
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
    }

    @Test
    void fencedToolCallRejectsExpiredLeaseBeforeWritingPartOrMessage() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 9, 59, 59)));

        AgentRunPartService service = new AgentRunPartService(parts, tasks, messages);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.upsertToolCall(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "call-1", "running", "run_tests", Map.of("strategy", "test"), 3, ""));

        assertEquals(StaleExecutionFenceException.Reason.EXPIRED_LEASE, error.reason());
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
    }

    @Test
    void fencedToolCallPersistsPartWhenTheFenceIsActive() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(41L);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.upsertAssistantTurn(7L, 3, "streaming")).thenReturn(message);
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(95L);
            return 1;
        });

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .upsertToolCall(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "call-1", "running", "run_tests", Map.of("strategy", "test"), 3, "");

        assertThat(result.getPartId()).isEqualTo(95L);
        assertThat(result.getPartKey()).isEqualTo("tool:call-1");
        verify(parts).insert(any(AgentRunPart.class));
    }

    @Test
    void fencedExistingToolCallRejectsStaleFenceBeforeWritingPart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunPartService service = new AgentRunPartService(parts, tasks, messages);
        assertThrows(StaleExecutionFenceException.class,
                () -> service.resolveExistingToolCall(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "call-1", "interrupted", "status=interrupted"));

        verify(parts, never()).updateById(any(AgentRunPart.class));
        verify(messages, never()).upsertAssistantTurn(anyLong(), anyLong(), anyString());
    }

    @Test
    void rejectedFencedToolCallLeaksNoSentinelIntoPartMessageOrErrorPayload() {
        String sentinel = "SENTINEL-SECRET-e4c27a";
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunPartService service = new AgentRunPartService(parts, tasks, messages);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.upsertToolCall(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "call-1", "running", "run_tests",
                        Map.of("strategy", sentinel), 3, sentinel));

        assertThat(error.getMessage()).doesNotContain(sentinel);
        // 失败发生在任何写入之前：Part 表、transcript message、事件/outbox（SSE 投影源）均无 sentinel。
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
        verify(messages, never()).upsertAssistantTurn(anyLong(), anyLong(), anyString());
        verify(messages, never()).recordEventMessage(anyLong(), anyString(), any(), anyLong());
    }

    private AgentTask fencedTask(String owner, long epoch, java.time.LocalDateTime leaseExpiresAt) {
        AgentTask task = task();
        task.setExecutionOwner(owner);
        task.setExecutionEpoch(epoch);
        task.setExecutionLeaseExpiresAt(leaseExpiresAt);
        return task;
    }

    @Test
    void currentEpochToolHistoryExcludesPendingAndPreviousEpochParts() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart current = new AgentRunPart();
        current.setTaskId(7L);
        current.setPartType("tool");
        current.setStatus("completed");
        current.setMetadata("{\"executionEpoch\":4}");
        AgentRunPart previous = new AgentRunPart();
        previous.setTaskId(7L);
        previous.setPartType("tool");
        previous.setStatus("completed");
        previous.setMetadata("{\"executionEpoch\":3}");
        AgentRunPart pending = new AgentRunPart();
        pending.setTaskId(7L);
        pending.setPartType("tool");
        pending.setStatus("pending");
        pending.setMetadata("{\"executionEpoch\":4}");
        when(parts.selectList(any())).thenReturn(List.of(current, previous, pending));

        List<AgentRunPart> history = new AgentRunPartService(parts, mock(AgentTaskMapper.class), messageService())
                .currentEpochToolHistory(7L, 4L);

        assertThat(history).containsExactly(current);
    }

    @Test
    void projectsLoopGuardProgressAsAStableCurrentEpochPart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(45L);
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "LOOP_GUARD_PROGRESS", Map.of(
                        "nonProgressIterations", 3,
                        "reason", "unfinished_plan"), 30L);

        assertThat(result.getPartKey()).isEqualTo("loop-guard:progress");
        assertThat(result.getPartType()).isEqualTo("loop_guard_progress");
        assertThat(result.getInputJson()).contains("nonProgressIterations").contains("3");
        assertThat(result.getMetadata()).contains("executionEpoch").contains("4");
    }

    @Test
    void projectsEachModelStepLifecycleOntoOneStablePart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(47L);
        java.util.concurrent.atomic.AtomicReference<AgentRunPart> stored = new java.util.concurrent.atomic.AtomicReference<>();
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(92L);
            stored.set(part);
            return 1;
        });
        when(parts.updateById(any(AgentRunPart.class))).thenReturn(1);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart started = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "MODEL_STEP_STARTED", Map.of(
                        "iteration", 5, "executionEpoch", 4L), 40L);
        AgentRunPart completed = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "MODEL_STEP_COMPLETED", Map.of(
                        "iteration", 5, "executionEpoch", 4L, "resultType", "tool_call"), 41L);

        assertThat(started.getPartId()).isEqualTo(92L);
        assertThat(completed.getPartKey()).isEqualTo("model-step:5");
        assertThat(completed.getPartType()).isEqualTo("model_step");
        assertThat(completed.getStatus()).isEqualTo("completed");
        assertThat(completed.getInputJson()).contains("resultType").contains("tool_call");
        assertThat(completed.getMetadata()).contains("executionEpoch").contains("4");
        verify(parts, times(1)).insert(any(AgentRunPart.class));
        verify(parts, times(1)).updateById(any(AgentRunPart.class));
    }

    @Test
    void recoveryInterruptsAnOpenModelStepPart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart open = new AgentRunPart();
        open.setPartType("model_step");
        open.setStatus("running");
        when(parts.selectList(any())).thenReturn(List.of(open));
        when(parts.updateById(open)).thenReturn(1);

        int interrupted = new AgentRunPartService(parts, mock(AgentTaskMapper.class), messageService())
                .interruptOpenParts(7L, "recovery restarted");

        assertEquals(1, interrupted);
        assertThat(open.getStatus()).isEqualTo("interrupted");
        assertThat(open.getOutputText()).isEqualTo("recovery restarted");
    }

    @Test
    void restoresLatestCurrentEpochLoopGuardProgressFromDurableEventParts() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart current = new AgentRunPart();
        current.setPartType("loop_guard_progress");
        current.setSequenceNumber(31L);
        current.setMetadata("{\"executionEpoch\":4}");
        current.setInputJson("{\"nonProgressIterations\":3}");
        AgentRunPart previous = new AgentRunPart();
        previous.setPartType("loop_guard_progress");
        previous.setSequenceNumber(32L);
        previous.setMetadata("{\"executionEpoch\":3}");
        previous.setInputJson("{\"nonProgressIterations\":8}");
        when(parts.selectList(any())).thenReturn(List.of(previous, current));

        int restored = new AgentRunPartService(parts, mock(AgentTaskMapper.class), messageService())
                .currentEpochLoopGuardProgress(7L, 4L);

        assertEquals(3, restored);
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setConversationId("conversation-7");
        task.setStudentId(11);
        task.setProjectId(22);
        task.setExecutionEpoch(4L);
        return task;
    }

    private AgentRunMessageService messageService() {
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(41L);
        when(messages.upsertAssistantTurn(anyLong(), anyLong(), anyString())).thenReturn(message);
        return messages;
    }

    @Test
    void persistsModelRetryLifecycleAsReplayableRetryParts() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(43L);
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart scheduled = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "RUN_MODEL_RETRY_SCHEDULED", Map.of(
                        "attempt", 1, "state", "retrying"), 19L);
        AgentRunPart started = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "RUN_MODEL_RETRY_STARTED", Map.of(
                        "attempt", 1, "state", "recovering"), 20L);

        assertThat(scheduled.getPartType()).isEqualTo("retry");
        assertThat(scheduled.getStatus()).isEqualTo("waiting");
        assertThat(scheduled.getPartKey()).isEqualTo("retry:19");
        assertThat(started.getPartType()).isEqualTo("retry");
        assertThat(started.getStatus()).isEqualTo("running");
        assertThat(started.getPartKey()).isEqualTo("retry:20");
    }

    @Test
    void finalEventPersistsOnlyVisibleContentAtTheDurablePartBoundary() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(44L);
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "FINAL", Map.of(
                        "content", "Visible &lt;thinking&gt;private plan&lt;/thinking&gt; answer"), 21L);

        assertThat(result.getOutputText()).isEqualTo("Visible  answer");
        assertThat(result.getInputJson()).doesNotContainIgnoringCase("think");
    }


    @Test
    void publicHistorySanitizesLegacyDirtyTextPartsWithoutRewritingRows() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart dirty = new AgentRunPart();
        dirty.setPartType("text");
        dirty.setOutputText("Visible <thinking>private plan</thinking> answer");
        when(parts.selectList(any())).thenReturn(List.of(dirty));

        List<Map<String, Object>> history = new AgentRunPartService(parts, tasks, messageService()).publicHistory(7L);

        assertThat(history).singleElement().extracting(item -> item.get("output"))
                .isEqualTo("Visible  answer");
        assertThat(dirty.getOutputText()).contains("private plan");
    }

    @Test
    void reasoningPartKeepsReasoningBodyButRemovesPrivateBlocksFromVisibleMetadata() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(46L);
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "THINK", Map.of(
                        "content", "\\<think\\>reasoning body\\</think\\>",
                        "summary", "\\<thinking\\>private title\\</thinking\\>Visible summary"), 23L);

        assertThat(result.getOutputText()).isEqualTo("reasoning body");
        assertThat(result.getInputJson()).contains("Visible summary");
        assertThat(result.getInputJson()).doesNotContain("private title");
        assertThat(result.getInputJson()).doesNotContainIgnoringCase("<think");
    }

    @Test
    void persistsToolExposureAsAnAuditableDurablePart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(48L);
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "TOOL_EXPOSURE", Map.of(
                        "runtimeProfile", "labex-native",
                        "mode", "build",
                        "schemaFingerprint", "fingerprint-71",
                        "definitions", List.of(Map.of("name", "read_file"))), 22L);

        assertThat(result.getPartType()).isEqualTo("tool_exposure");
        assertThat(result.getPartKey()).isEqualTo("tool-exposure:labex-native:build");
        assertThat(result.getInputJson()).contains("schemaFingerprint").contains("fingerprint-71");
    }

    @Test
    void persistsVisibleUnverifiedNativeFinalAlongsideItsEvidence() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(47L);
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart evidence = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "COMPLETION_EVIDENCE", Map.of(
                        "satisfied", false,
                        "finalResponseVisible", true,
                        "finalizationStatus", "unverified",
                        "changedFiles", List.of("skills/SKILL.md")), 24L);
        AgentRunPart finalReply = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "FINAL", Map.of(
                        "content", "已删除 skill，仍需要补充验证。",
                        "completionStatus", "unverified"), 25L);

        assertThat(evidence.getPartType()).isEqualTo("completion_evidence");
        assertThat(evidence.getStatus()).isEqualTo("error");
        assertThat(evidence.getInputJson())
                .contains("finalResponseVisible").contains("unverified").contains("skills/SKILL.md");
        assertThat(finalReply.getPartType()).isEqualTo("text");
        assertThat(finalReply.getStatus()).isEqualTo("completed");
        assertThat(finalReply.getOutputText()).isEqualTo("已删除 skill，仍需要补充验证。");
    }

    @Test
    void persistsFinalizationBlockerAsOneStableCurrentEpochPart() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageService messages = mock(AgentRunMessageService.class);
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(46L);
        when(messages.recordEventMessage(anyLong(), anyString(), any(), anyLong())).thenReturn(message);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunPart result = new AgentRunPartService(parts, tasks, messages)
                .recordEventPart(7L, "FINALIZATION_BLOCKED", Map.of(
                        "evidenceFingerprint", "evidence-a", "recoveryAttempt", 1, "recoveryAllowed", true), 24L);

        assertThat(result.getPartType()).isEqualTo("finalization_blocker");
        assertThat(result.getPartKey()).isEqualTo("finalization:evidence-a");
        assertThat(result.getStatus()).isEqualTo("waiting");
    }

    @Test
    void restoresFinalizationRecoveryAttemptsOnlyFromTheCurrentEpoch() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart current = new AgentRunPart();
        current.setPartType("finalization_blocker");
        current.setSequenceNumber(31L);
        current.setMetadata("{\"executionEpoch\":4}");
        current.setInputJson("{\"evidenceFingerprint\":\"evidence-a\",\"recoveryAttempt\":1}");
        AgentRunPart previous = new AgentRunPart();
        previous.setPartType("finalization_blocker");
        previous.setSequenceNumber(32L);
        previous.setMetadata("{\"executionEpoch\":3}");
        previous.setInputJson("{\"evidenceFingerprint\":\"evidence-a\",\"recoveryAttempt\":8}");
        when(parts.selectList(any())).thenReturn(List.of(previous, current));

        int restored = new AgentRunPartService(parts, mock(AgentTaskMapper.class), messageService())
                .currentEpochFinalizationRecoveryAttempts(7L, 4L, "evidence-a");

        assertEquals(1, restored);
    }

}
