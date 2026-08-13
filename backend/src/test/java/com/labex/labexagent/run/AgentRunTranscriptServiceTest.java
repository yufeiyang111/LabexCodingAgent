package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunExecutionLeaseService.StaleExecutionFenceException;
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
    void putsAContinuationNoteIntoTheResolvedInteractionResult() {
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

        AgentRunInteraction answered = new AgentRunInteraction();
        answered.setInteractionId("question-answered");
        answered.setInteractionType("question");
        answered.setStatus("answered");
        answered.setRequestPayload("{\"question\":\"Continue?\"}");
        answered.setResponsePayload("{\"answer\":\"yes\"}");
        AgentRunInteraction cancelled = new AgentRunInteraction();
        cancelled.setInteractionId("question-cancelled");
        cancelled.setInteractionType("question");
        cancelled.setStatus("cancelled");
        cancelled.setRequestPayload("{\"question\":\"Continue?\"}");
        cancelled.setResponsePayload("{\"action\":\"cancel\",\"feedback\":\"\"}");

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        List<Map<String, Object>> projection = service.loadProjectableTranscriptForInteractionResume(7L);

        Map<String, Object> answeredResult = service.resolvedInteractionToolResult(answered, projection);
        assertThat(String.valueOf(answeredResult.get("content")))
                .contains("answered").contains("yes")
                .contains("用户已回答你的提问");

        Map<String, Object> cancelledResult = service.resolvedInteractionToolResult(cancelled, projection);
        assertThat(String.valueOf(cancelledResult.get("content")))
                .contains("cancelled").contains("用户取消了本次提问");
    }

    @Test
    void resolvesEveryToolCallInABatchAfterInteractionPause() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessage user = message(1L, "provider:4:message:0", 0L, "user", "start");
        AgentRunMessage assistant = message(2L, "provider:4:message:1", 1L, "assistant", "");
        AgentRunPart approvalCall = toolCallPart(11L, 2L, "call-approval", "read_file", "waiting_approval", 1000L);
        AgentRunPart skippedCall = toolCallPart(12L, 2L, "call-skipped", "grep", "skipped", 1001L);
        skippedCall.setOutputText("Skipped because another call is waiting for approval.");
        when(messages.selectList(any())).thenReturn(List.of(user, assistant));
        when(parts.selectList(any())).thenReturn(List.of(approvalCall, skippedCall));

        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setTaskId(7L);
        interaction.setInteractionId("permission-1");
        interaction.setInteractionType("permission");
        interaction.setStatus("approved");
        interaction.setRequestPayload("{\"toolName\":\"read_file\"}");
        interaction.setResponsePayload("{\"action\":\"allow_once\"}");

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        List<Map<String, Object>> projection = service.loadProjectableTranscriptForInteractionResume(7L);
        List<Map<String, Object>> results = service.resolvedInteractionToolResults(interaction, projection);

        assertThat(results).extracting(result -> result.get("tool_call_id"))
                .containsExactly("call-approval", "call-skipped");
        assertThat(String.valueOf(results.get(0).get("content"))).contains("approved");
        assertThat(String.valueOf(results.get(1).get("content"))).contains("skipped").contains("Skipped because");
    }

    @Test
    void selectsTheInteractionToolCallByDurableIdentityWhenToolNamesRepeat() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessage user = message(1L, "provider:4:message:0", 0L, "user", "start");
        AgentRunMessage assistant = message(2L, "provider:4:message:1", 1L, "assistant", "");
        AgentRunPart skippedCall = toolCallPart(11L, 2L, "call-skipped", "read_file", "skipped", 1000L);
        skippedCall.setOutputText("Skipped because another call is waiting for approval.");
        AgentRunPart approvalCall = toolCallPart(12L, 2L, "call-approval", "read_file", "waiting_approval", 1001L);
        when(messages.selectList(any())).thenReturn(List.of(user, assistant));
        when(parts.selectList(any())).thenReturn(List.of(skippedCall, approvalCall));

        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setTaskId(7L);
        interaction.setInteractionId("permission-duplicate-name");
        interaction.setInteractionType("permission");
        interaction.setStatus("approved");
        interaction.setRequestPayload("{\"toolName\":\"read_file\",\"toolCallId\":\"call-approval\"}");
        interaction.setResponsePayload("{\"action\":\"allow_once\"}");

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        List<Map<String, Object>> projection = service.loadProjectableTranscriptForInteractionResume(7L);
        List<Map<String, Object>> results = service.resolvedInteractionToolResults(interaction, projection);

        assertThat(results).extracting(result -> result.get("tool_call_id"))
                .containsExactly("call-skipped", "call-approval");
        assertThat(String.valueOf(results.get(0).get("content"))).contains("skipped");
        assertThat(String.valueOf(results.get(1).get("content"))).contains("approved");
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

    @Test
    void persistsDeferredCommandResultAsTheMissingProviderToolMessage() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart call = toolCallPart(91L, 41L, "approval-call", "run_tests", "waiting_approval", 8L);
        AgentTask task = task();
        task.setExecutionEpoch(5L);
        when(parts.selectOne(any())).thenReturn(null, call, null);
        when(parts.selectList(any())).thenReturn(List.of(call));
        when(messages.selectList(any())).thenReturn(List.of(
                message(41L, "provider:4:message:8", 8L, "assistant", "")));
        when(messages.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task);
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(92L);
            return 1;
        });
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(93L);
            return 1;
        });

        boolean appended = new AgentRunTranscriptService(messages, parts, tasks)
                .appendDeferredToolResult(7L, "approval-call", "run_tests", "status=failed\nexit=128");

        assertThat(appended).isTrue();
        AgentRunMessage result = capturedMessage(messages);
        assertThat(result.getMessageKey()).isEqualTo("provider:5:message:9");
        assertThat(result.getRole()).isEqualTo("tool");
        assertThat(result.getContent()).contains("status=failed");
        verify(parts).updateById(org.mockito.ArgumentMatchers.argThat(part ->
                "tool_call".equals(part.getPartType())
                        && "approval-call".equals(part.getToolCallId())
                        && "completed".equals(part.getStatus())
                        && part.getOutputText().contains("exit=128")));
    }

    @Test
    void replacesWaitingPlaceholderWithTheFinalDeferredCommandResult() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart placeholder = new AgentRunPart();
        placeholder.setPartId(94L);
        placeholder.setTaskId(7L);
        placeholder.setMessageId(42L);
        placeholder.setPartType("tool_result");
        placeholder.setToolCallId("approval-call");
        placeholder.setToolName("run_tests");
        placeholder.setStatus("completed");
        placeholder.setOutputText("command approval required");
        placeholder.setMetadata("{\"provider\":true,\"partType\":\"tool_result\"}");
        AgentRunPart call = toolCallPart(91L, 41L, "approval-call", "run_tests", "waiting_approval", 8L);
        AgentRunMessage placeholderMessage = message(42L, "provider:4:message:9", 9L,
                "tool", "command approval required");
        when(parts.selectOne(any())).thenReturn(placeholder, call, placeholder);
        when(parts.selectList(any())).thenReturn(List.of(call));
        when(messages.selectById(42L)).thenReturn(placeholderMessage);
        when(tasks.selectById(7L)).thenReturn(task());
        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);

        boolean appended = service.appendDeferredToolResult(
                7L, "approval-call", "run_tests", "status=failed\nexit=128");

        assertThat(appended).isTrue();
        assertThat(placeholderMessage.getContent()).isEqualTo("status=failed\nexit=128");
        assertThat(placeholder.getOutputText()).isEqualTo("status=failed\nexit=128");
        assertThat(placeholder.getMetadata()).contains("deferredResolution");
        assertThat(call.getStatus()).isEqualTo("completed");
        assertThat(service.hasPersistedToolResult(7L, "approval-call")).isTrue();
        verify(messages).updateById(placeholderMessage);
        verify(parts).updateById(placeholder);
    }

    @Test
    void doesNotTreatAnUnmarkedWaitingToolResultAsADeferredCommandOutcome() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunPart placeholder = new AgentRunPart();
        placeholder.setPartType("tool_result");
        placeholder.setToolCallId("approval-call");
        placeholder.setStatus("completed");
        placeholder.setMetadata("{\"provider\":true,\"partType\":\"tool_result\"}");
        when(parts.selectOne(any())).thenReturn(placeholder);

        boolean ready = new AgentRunTranscriptService(messages, parts, tasks)
                .hasPersistedToolResult(7L, "approval-call");

        assertThat(ready).isFalse();
    }

    @Test
    void fencedAppendMessageRejectsStaleOwnerBeforeWritingTranscript() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.appendMessage(new ExecutionFence(7L, "instance-a", 4L),
                        7L, 3L, 2L, Map.of("role", "user", "content", "continue")));

        assertEquals(StaleExecutionFenceException.Reason.STALE_OWNER, error.reason());
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
    }

    @Test
    void fencedAppendMessageRejectsStaleEpochBeforeWritingTranscript() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.appendMessage(new ExecutionFence(7L, "instance-a", 3L),
                        7L, 3L, 2L, Map.of("role", "user", "content", "continue")));

        assertEquals(StaleExecutionFenceException.Reason.STALE_EPOCH, error.reason());
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
    }

    @Test
    void fencedAppendMessageRejectsEpochMismatchBeforeWritingTranscript() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.appendMessage(new ExecutionFence(7L, "instance-a", 4L),
                        7L, 3L, 2L, Map.of("role", "user", "content", "continue")));

        // fence 本身有效（owner/epoch/lease 均匹配），但调用方声明的 executionEpoch 与 fence epoch 不一致。
        assertEquals(StaleExecutionFenceException.Reason.STALE_FENCE, error.reason());
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
    }

    @Test
    void fencedAppendMessageRejectsExpiredLeaseBeforeWritingTranscript() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 9, 59, 59)));

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.appendMessage(new ExecutionFence(7L, "instance-a", 4L),
                        7L, 3L, 2L, Map.of("role", "user", "content", "continue")));

        assertEquals(StaleExecutionFenceException.Reason.EXPIRED_LEASE, error.reason());
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
    }

    @Test
    void fencedAppendMessagePersistsMessageAndPartWhenTheFenceIsActive() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(messages.selectOne(any())).thenReturn(null);
        when(parts.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(51L);
            return 1;
        });
        when(parts.insert(any(AgentRunPart.class))).thenAnswer(invocation -> {
            AgentRunPart part = invocation.getArgument(0);
            part.setPartId(96L);
            return 1;
        });

        Map<String, Object> function = Map.of("name", "run_tests", "arguments", "{}");
        Map<String, Object> call = Map.of("id", "call-1", "type", "function", "function", function);
        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        service.appendMessage(new ExecutionFence(7L, "instance-a", 4L), 7L, 4L, 2L,
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));

        assertThat(capturedMessage(messages).getMessageKey()).isEqualTo("provider:4:message:2");
        assertThat(capturedPart(parts).getPartType()).isEqualTo("tool_call");
    }

    @Test
    void rejectedFencedTranscriptWriteLeaksNoSentinelIntoMessagePartOrErrorPayload() {
        String sentinel = "SENTINEL-SECRET-71ab09";
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(fencedTask("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunTranscriptService service = new AgentRunTranscriptService(messages, parts, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.appendMessage(new ExecutionFence(7L, "instance-a", 4L),
                        7L, 3L, 2L, Map.of("role", "assistant", "content", sentinel,
                                "tool_calls", List.of(Map.of("id", "call-1", "type", "function",
                                        "function", Map.of("name", "run_tests", "arguments", "{}"))))));

        assertFalse(error.getMessage().contains(sentinel));
        // transcript message 与 Part 均未写入；事件/outbox 由 lifecycle 单独签发，此处无任何投影源。
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
        verify(parts, never()).insert(any(AgentRunPart.class));
        verify(parts, never()).updateById(any(AgentRunPart.class));
    }

    private AgentTask fencedTask(String owner, long epoch, java.time.LocalDateTime leaseExpiresAt) {
        AgentTask task = task();
        task.setExecutionOwner(owner);
        task.setExecutionEpoch(epoch);
        task.setExecutionLeaseExpiresAt(leaseExpiresAt);
        return task;
    }

    private AgentRunPart toolCallPart(Long partId, Long messageId, String toolCallId,
                                              String toolName, String status, Long sequence) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(partId);
        part.setTaskId(7L);
        part.setMessageId(messageId);
        part.setPartKey("provider:4:tool-call:1:" + sequence + ":" + toolCallId);
        part.setPartType("tool_call");
        part.setToolCallId(toolCallId);
        part.setToolName(toolName);
        part.setStatus(status);
        part.setSequenceNumber(sequence);
        part.setInputJson("{\"id\":\"" + toolCallId + "\",\"type\":\"function\",\"function\":{\"name\":\""
                + toolName + "\",\"arguments\":\"{}\"}}");
        return part;
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
    void persistsComposedProviderContextMessagesWithoutSilentTruncation() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(messages.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(41L);
            return 1;
        });

        // 首条上下文消息由多区段组装，最坏约 139k 字符；12k 硬截断会把会话记忆整段切掉。
        String composedContext = "x".repeat(150_000);
        new AgentRunTranscriptService(messages, parts, tasks)
                .appendMessage(7L, 1L, 0L, Map.of("role", "user", "content", composedContext));

        assertThat(capturedMessage(messages).getContent())
                .hasSize(150_000)
                .doesNotContain("...truncated...");
    }

    @Test
    void persistsProviderMessagesOfAnySizeWithoutTruncation() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(messages.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(41L);
            return 1;
        });

        // 超过任何历史硬上限（12k/200k）的内容也必须完整落库；截断只属于请求构建期的准入/压缩策略。
        String oversized = "y".repeat(250_000);
        new AgentRunTranscriptService(messages, parts, tasks)
                .appendMessage(7L, 1L, 0L, Map.of("role", "user", "content", oversized));

        assertThat(capturedMessage(messages).getContent())
                .hasSize(250_000)
                .doesNotContain("...truncated...");
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
