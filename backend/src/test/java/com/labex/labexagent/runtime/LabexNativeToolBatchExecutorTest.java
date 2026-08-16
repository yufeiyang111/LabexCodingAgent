package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class LabexNativeToolBatchExecutorTest {

    @Test
    void persistsEveryCallBeforeSerialExecutionAndPairsEachToolResultWithItsProviderId() throws Exception {
        AgentProviderTranscriptAppender transcript = mock(AgentProviderTranscriptAppender.class);
        AgentToolCallJournalService journal = mock(AgentToolCallJournalService.class);
        LabexNativeToolBatchExecutor.ToolExecutionDelegate delegate = mock(
                LabexNativeToolBatchExecutor.ToolExecutionDelegate.class);
        LabexNativeToolBatchExecutor executor = new LabexNativeToolBatchExecutor(
                new AgentToolCallBatchProtocol(), transcript, journal);
        LabexNativeToolBatchExecutor.Admission first = admission("read_file", "call-read", 0);
        LabexNativeToolBatchExecutor.Admission second = admission("list_files", "call-list", 1);
        AtomicBoolean secondStartedAfterFirst = new AtomicBoolean(false);
        ToolResult firstResult = ToolResult.ok("README");
        ToolResult secondResult = ToolResult.ok("src");

        when(delegate.execute(first)).thenAnswer(invocation ->
                LabexNativeToolBatchExecutor.CallExecution.completed(firstResult));
        when(delegate.execute(second)).thenAnswer(invocation -> {
            secondStartedAfterFirst.set(true);
            return LabexNativeToolBatchExecutor.CallExecution.completed(secondResult);
        });

        LabexNativeToolBatchExecutor.BatchResult result = executor.execute(
                request(List.of(first, second), () -> false),
                delegate,
                (admission, toolResult, kind) -> kind.name() + ":" + admission.call().toolCallId());

        assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.CONTINUE);
        assertThat(result.outcomes()).extracting(outcome -> outcome.admission().call().toolCallId())
                .containsExactly("call-read", "call-list");
        assertThat(secondStartedAfterFirst).isTrue();

        InOrder order = inOrder(transcript, journal, delegate);
        order.verify(transcript).append(any(ExecutionFence.class), eq(71L), eq(4L), any(Map.class));
        order.verify(journal).pending(any(ExecutionFence.class), eq(71L), eq("call-read"), eq("read_file"),
                any(), eq(3));
        order.verify(journal).pending(any(ExecutionFence.class), eq(71L), eq("call-list"), eq("list_files"),
                any(), eq(3));
        order.verify(delegate).execute(first);
        order.verify(journal).completed(any(ExecutionFence.class), eq(71L), eq("call-read"), eq("read_file"),
                any(), eq(3), org.mockito.ArgumentMatchers.same(firstResult));
        order.verify(transcript).append(any(ExecutionFence.class), eq(71L), eq(4L), any(Map.class));
        order.verify(delegate).execute(second);
        order.verify(journal).completed(any(ExecutionFence.class), eq(71L), eq("call-list"), eq("list_files"),
                any(), eq(3), org.mockito.ArgumentMatchers.same(secondResult));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> messages = ArgumentCaptor.forClass(Map.class);
        verify(transcript, org.mockito.Mockito.times(3)).append(any(ExecutionFence.class), eq(71L), eq(4L), messages.capture());
        List<Map<String, Object>> persisted = messages.getAllValues();
        assertThat(persisted.get(0)).containsEntry("role", "assistant");
        assertThat((List<?>) persisted.get(0).get("tool_calls")).hasSize(2);
        assertThat(persisted.subList(1, 3))
                .extracting(message -> message.get("tool_call_id"))
                .containsExactly("call-read", "call-list");
    }

    @Test
    void waitsForApprovalAndMarksEveryRemainingAllowedCallAsSkipped() throws Exception {
        AgentProviderTranscriptAppender transcript = mock(AgentProviderTranscriptAppender.class);
        AgentToolCallJournalService journal = mock(AgentToolCallJournalService.class);
        LabexNativeToolBatchExecutor.ToolExecutionDelegate delegate = mock(
                LabexNativeToolBatchExecutor.ToolExecutionDelegate.class);
        LabexNativeToolBatchExecutor executor = new LabexNativeToolBatchExecutor(
                new AgentToolCallBatchProtocol(), transcript, journal);
        LabexNativeToolBatchExecutor.Admission first = admission("bash", "call-install", 0);
        LabexNativeToolBatchExecutor.Admission second = admission("read_file", "call-read", 1);
        ToolResult approval = ToolResult.commandApprovalRequired(
                "approval needed", "approval-1", "npm install", "medium", "mutating", "2099-01-01T00:00:00");
        when(delegate.execute(first)).thenReturn(LabexNativeToolBatchExecutor.CallExecution.completed(approval));

        LabexNativeToolBatchExecutor.BatchResult result = executor.execute(
                request(List.of(first, second), () -> false),
                delegate,
                (admission, toolResult, kind) -> kind.name() + ":" + admission.call().toolCallId());

        assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.WAITING_APPROVAL);
        verify(journal).waitingApproval(any(ExecutionFence.class), eq(71L), eq("call-install"), eq("bash"),
                any(), eq(3), eq("approval-1"));
        verify(journal).skipped(any(ExecutionFence.class), eq(71L), eq("call-read"), eq("read_file"),
                any(), eq(3), org.mockito.ArgumentMatchers.contains("waiting for approval"));
        verify(delegate, never()).execute(second);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> messages = ArgumentCaptor.forClass(Map.class);
        verify(transcript, org.mockito.Mockito.times(2)).append(any(ExecutionFence.class), eq(71L), eq(4L), messages.capture());
        assertThat(messages.getAllValues())
                .extracting(message -> message.get("tool_call_id"))
                .containsExactly(null, "call-read");
    }

    @Test
    void interruptsRemainingCallsWhenCancellationArrivesBetweenSerialToolExecutions() throws Exception {
        AgentProviderTranscriptAppender transcript = mock(AgentProviderTranscriptAppender.class);
        AgentToolCallJournalService journal = mock(AgentToolCallJournalService.class);
        LabexNativeToolBatchExecutor.ToolExecutionDelegate delegate = mock(
                LabexNativeToolBatchExecutor.ToolExecutionDelegate.class);
        LabexNativeToolBatchExecutor executor = new LabexNativeToolBatchExecutor(
                new AgentToolCallBatchProtocol(), transcript, journal);
        LabexNativeToolBatchExecutor.Admission first = admission("read_file", "call-first", 0);
        LabexNativeToolBatchExecutor.Admission second = admission("list_files", "call-second", 1);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(delegate.execute(first)).thenAnswer(invocation -> {
            cancelled.set(true);
            return LabexNativeToolBatchExecutor.CallExecution.completed(ToolResult.ok("first"));
        });

        LabexNativeToolBatchExecutor.BatchResult result = executor.execute(
                request(List.of(first, second), cancelled::get),
                delegate,
                (admission, toolResult, kind) -> kind.name() + ":" + admission.call().toolCallId());

        assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.CANCELLED);
        verify(delegate, never()).execute(second);
        verify(journal).interrupted(any(ExecutionFence.class), eq(71L), eq("call-second"), eq("list_files"),
                any(), eq(3), org.mockito.ArgumentMatchers.contains("cancelled"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> messages = ArgumentCaptor.forClass(Map.class);
        verify(transcript, org.mockito.Mockito.times(3)).append(any(ExecutionFence.class), eq(71L), eq(4L), messages.capture());
        assertThat(messages.getAllValues())
                .extracting(message -> message.get("tool_call_id"))
                .containsExactly(null, "call-first", "call-second");
    }

    private LabexNativeToolBatchExecutor.BatchRequest request(
            List<LabexNativeToolBatchExecutor.Admission> admissions,
            LabexNativeToolBatchExecutor.CancellationProbe cancellationProbe) {
        return new LabexNativeToolBatchExecutor.BatchRequest(
                new ExecutionFence(71L, "instance-a", 4L), 71L, 4L, 3,
                "", admissions, cancellationProbe);
    }

    private LabexNativeToolBatchExecutor.Admission admission(String toolName, String toolCallId, int index) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", toolName + "-target");
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(toolName).description(toolName).build();
            }

            @Override
            public ToolResult execute(AgentContext context, JsonObject ignored) {
                return ToolResult.ok("unused");
            }
        };
        return new LabexNativeToolBatchExecutor.Admission(
                new AgentModelTurnExecutor.NativeToolCall(toolName, arguments.toString(), toolCallId, index),
                AgentToolTurnExecutor.ToolInputResolution.allowed(arguments, tool), arguments.deepCopy());
    }
}
