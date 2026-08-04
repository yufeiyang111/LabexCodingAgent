package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
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
        verify(parts).insert(any(AgentRunPart.class));
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

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setConversationId("conversation-7");
        task.setStudentId(11);
        task.setProjectId(22);
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

}
