package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunTranscriptServiceTest {

    @Test
    void persistsAssistantToolBatchAsOneMessageAndStableToolPart() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(messages.selectOne(any())).thenReturn(null);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(41L);
            return 1;
        });
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(91L);
            return 1;
        });

        Map<String, Object> function = Map.of("name", "run_tests", "arguments", "{\"strategy\":\"test\"}");
        Map<String, Object> call = Map.of("id", "call-1", "type", "function", "function", function);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "");
        assistant.put("tool_calls", List.of(call));

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        service.appendMessage(7L, 3L, 2L, assistant);

        assertThat(capturedMessage(messages).getMessageKey()).isEqualTo("provider:3:message:2");
        assertThat(capturedMessage(messages).getRole()).isEqualTo("assistant");
        AgentRunPart storedPart = capturedPart(parts);
        assertThat(storedPart.getPartKey()).contains("provider:3:tool-call:2:0:call-1");
        assertThat(storedPart.getToolCallId()).isEqualTo("call-1");
        assertThat(storedPart.getToolName()).isEqualTo("run_tests");
        assertThat(storedPart.getInputJson()).contains("run_tests").contains("strategy");
    }

    @Test
    void rebuildsAssistantAndToolMessagesWithNativeProtocolFields() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);

        AgentRunMessage assistant = message(1L, "provider:3:message:2", 2L, "assistant", "");
        AgentRunMessage tool = message(2L, "provider:3:message:3", 3L, "tool", "passed");
        tool.setMetadata("{\"tool_call_id\":\"call-1\",\"name\":\"run_tests\"}");
        AgentRunPart callPart = new AgentRunPart();
        callPart.setPartId(11L);
        callPart.setMessageId(1L);
        callPart.setPartKey("provider:3:tool-call:2:0:call-1");
        callPart.setPartType("tool_call");
        callPart.setStatus("completed");
        callPart.setSequenceNumber(2000L);
        callPart.setInputJson("{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"run_tests\",\"arguments\":\"{}\"}}");
        when(messages.selectList(any())).thenReturn(List.of(assistant, tool));
        when(parts.selectList(any())).thenReturn(List.of(callPart));

        List<Map<String, Object>> result = new AgentRunTranscriptService(messages, parts, tasks)
                .loadProjectableTranscript(7L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("role", "assistant");
        assertThat((List<?>) result.get(0).get("tool_calls")).hasSize(1);
        assertThat(result.get(1)).containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-1")
                .containsEntry("name", "run_tests")
                .containsEntry("content", "passed");
    }

    @Test
    void restoresOnlyTheCompletePrefixWhenAToolBatchWasInterrupted() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);

        AgentRunMessage user = message(1L, "provider:3:message:0", 0L, "user", "continue task");
        AgentRunMessage assistant = message(2L, "provider:3:message:1", 1L, "assistant", "");
        AgentRunMessage resumedUser = message(3L, "provider:3:message:2", 2L, "user", "approved, continue");
        AgentRunPart pendingCall = new AgentRunPart();
        pendingCall.setPartId(11L);
        pendingCall.setMessageId(2L);
        pendingCall.setPartKey("provider:3:tool-call:1:0:call-pending");
        pendingCall.setPartType("tool_call");
        pendingCall.setStatus("pending");
        pendingCall.setSequenceNumber(1000L);
        pendingCall.setInputJson("{\"id\":\"call-pending\",\"type\":\"function\",\"function\":{\"name\":\"shell\",\"arguments\":\"{}\"}}");
        when(messages.selectList(any())).thenReturn(List.of(user, assistant, resumedUser));
        when(parts.selectList(any())).thenReturn(List.of(pendingCall));

        List<Map<String, Object>> result = new AgentRunTranscriptService(messages, parts, tasks)
                .loadProjectableTranscript(7L);

        assertThat(result).containsExactly(
                Map.of("role", "user", "content", "continue task"),
                Map.of("role", "user", "content", "approved, continue"));
    }

    @Test
    void resolvesAnAnsweredInteractionToAProtocolToolResult() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);

        AgentRunMessage user = message(1L, "provider:3:message:0", 0L, "user", "start");
        AgentRunMessage assistant = message(2L, "provider:3:message:1", 1L, "assistant", "");
        AgentRunPart openCall = new AgentRunPart();
        openCall.setPartId(11L);
        openCall.setMessageId(2L);
        openCall.setPartKey("provider:3:tool-call:1:0:call-question");
        openCall.setPartType("tool_call");
        openCall.setStatus("waiting_user");
        openCall.setSequenceNumber(1000L);
        openCall.setInputJson("{\"id\":\"call-question\",\"type\":\"function\",\"function\":{\"name\":\"question\",\"arguments\":\"{}\"}}");
        when(messages.selectList(any())).thenReturn(List.of(user, assistant));
        when(parts.selectList(any())).thenReturn(List.of(openCall));

        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId("question-1");
        interaction.setInteractionType("question");
        interaction.setStatus("answered");
        interaction.setRequestPayload("{\"question\":\"Continue?\"}");
        interaction.setResponsePayload("{\"answer\":\"yes\"}");

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        List<Map<String, Object>> projection = service.loadProjectableTranscriptForInteractionResume(7L);
        Map<String, Object> result = service.resolvedInteractionToolResult(interaction, projection);

        assertThat(projection).hasSize(2);
        assertThat(result).containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-question")
                .containsEntry("name", "question");
        assertThat(String.valueOf(result.get("content"))).contains("answered").contains("yes");
        assertThat(service.resolvedInteractionToolResult(interaction,
                List.of(projection.get(0), projection.get(1), result))).isNull();
    }

    @Test
    void rejectsMalformedToolMessageBeforeWritingFact() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        Map<String, Object> malformed = Map.of("role", "tool", "content", "orphan");

        assertThatThrownBy(() -> new AgentRunTranscriptService(messages, parts, tasks)
                .appendMessage(7L, 1L, 0L, malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool_call_id");
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setConversationId("conversation-7");
        task.setStudentId(11);
        task.setProjectId(22);
        return task;
    }

    private AgentRunMessage message(Long id, String key, Long sequence, String role, String content) {
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(id);
        message.setTaskId(7L);
        message.setMessageKey(key);
        message.setSequenceNumber(sequence);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private AgentRunMessage capturedMessage(AgentRunMessageMapper mapper) {
        org.mockito.ArgumentCaptor<AgentRunMessage> captor = org.mockito.ArgumentCaptor.forClass(AgentRunMessage.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private AgentRunPart capturedPart(AgentRunPartMapper mapper) {
        org.mockito.ArgumentCaptor<AgentRunPart> captor = org.mockito.ArgumentCaptor.forClass(AgentRunPart.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
    @Test
    void loadsOnlyProtocolSafeFactsAppendedAfterCompactionBoundary() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(messages.selectList(any())).thenReturn(List.of(
                message(1L, "provider:3:message:8", 8L, "user", "before"),
                message(2L, "provider:3:message:10", 10L, "user", "after"),
                message(3L, "provider:3:message:11", 11L, "assistant", "continued")));
        when(parts.selectList(any())).thenReturn(List.of());

        List<Map<String, Object>> result = new AgentRunTranscriptService(messages, parts, tasks)
                .loadProjectableTranscriptAfter(7L, 9L);

        assertThat(result).containsExactly(
                Map.of("role", "user", "content", "after"),
                Map.of("role", "assistant", "content", "continued"));
    }
}
