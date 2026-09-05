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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 对齐 OpenCode / AI SDK 的并发工具调度：同一 turn 的全部工具并发派发，
 * durable 结果仍按 Provider 顺序投影；首个终态指令中断剩余在跑调用。
 */
class LabexNativeToolBatchExecutorTest {

    @Test
    void persistsEveryCallBeforeConcurrentExecutionAndProjectsResultsInProviderOrder() throws Exception {
        AgentProviderTranscriptAppender transcript = mock(AgentProviderTranscriptAppender.class);
        AgentToolCallJournalService journal = mock(AgentToolCallJournalService.class);
        LabexNativeToolBatchExecutor.ToolExecutionDelegate delegate = mock(
                LabexNativeToolBatchExecutor.ToolExecutionDelegate.class);
        LabexNativeToolBatchExecutor executor = new LabexNativeToolBatchExecutor(
                new AgentToolCallBatchProtocol(), transcript, journal);
        LabexNativeToolBatchExecutor.Admission first = admission("read_file", "call-read", 0);
        LabexNativeToolBatchExecutor.Admission second = admission("list_files", "call-list", 1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        ToolResult firstResult = ToolResult.ok("README");
        ToolResult secondResult = ToolResult.ok("src");

        when(delegate.execute(first)).thenAnswer(invocation -> {
            // 第一个工具必须等到第二个工具已经并发开始后才返回 —— 串行实现下该断言必失败。
            assertThat(secondEntered.await(5, TimeUnit.SECONDS))
                    .as("second tool must start before first completes (concurrent dispatch)")
                    .isTrue();
            return LabexNativeToolBatchExecutor.CallExecution.completed(firstResult);
        });
        when(delegate.execute(second)).thenAnswer(invocation -> {
            secondEntered.countDown();
            return LabexNativeToolBatchExecutor.CallExecution.completed(secondResult);
        });

        LabexNativeToolBatchExecutor.BatchResult result = executor.execute(
                request(List.of(first, second), () -> false),
                delegate,
                (admission, toolResult, kind) -> kind.name() + ":" + admission.call().toolCallId());

        assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.CONTINUE);
        assertThat(result.outcomes()).extracting(outcome -> outcome.admission().call().toolCallId())
                .containsExactly("call-read", "call-list");

        InOrder order = inOrder(transcript, journal);
        order.verify(transcript).append(any(ExecutionFence.class), eq(71L), eq(4L), any(Map.class));
        order.verify(journal).pending(any(ExecutionFence.class), eq(71L), eq("call-read"), eq("read_file"),
                any(), eq(3));
        order.verify(journal).pending(any(ExecutionFence.class), eq(71L), eq("call-list"), eq("list_files"),
                any(), eq(3));
        // 结果投影严格按 Provider 顺序，与执行完成顺序无关。
        order.verify(journal).completed(any(ExecutionFence.class), eq(71L), eq("call-read"), eq("read_file"),
                any(), eq(3), org.mockito.ArgumentMatchers.same(firstResult));
        order.verify(transcript).append(any(ExecutionFence.class), eq(71L), eq(4L), any(Map.class));
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
    void waitsForApprovalAndInterruptsStillRunningRemainingCalls() throws Exception {
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
        CountDownLatch secondStarted = new CountDownLatch(1);
        when(delegate.execute(first)).thenReturn(LabexNativeToolBatchExecutor.CallExecution.completed(approval));
        // 第二个工具已并发开始但长时间不返回：首个终态（等待审批）应中断它并标记 skipped。
        when(delegate.execute(second)).thenAnswer(invocation -> {
            secondStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return LabexNativeToolBatchExecutor.CallExecution.completed(ToolResult.ok("never"));
        });

        LabexNativeToolBatchExecutor.BatchResult result = executor.execute(
                request(List.of(first, second), () -> false),
                delegate,
                (admission, toolResult, kind) -> kind.name() + ":" + admission.call().toolCallId());

        assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.WAITING_APPROVAL);
        assertThat(secondStarted.await(3, TimeUnit.SECONDS))
                .as("second tool must have been dispatched concurrently before the approval terminal")
                .isTrue();
        verify(journal).waitingApproval(any(ExecutionFence.class), eq(71L), eq("call-install"), eq("bash"),
                any(), eq(3), eq("approval-1"));
        verify(journal).skipped(any(ExecutionFence.class), eq(71L), eq("call-read"), eq("read_file"),
                any(), eq(3), org.mockito.ArgumentMatchers.contains("waiting for approval"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> messages = ArgumentCaptor.forClass(Map.class);
        verify(transcript, org.mockito.Mockito.times(2)).append(any(ExecutionFence.class), eq(71L), eq(4L), messages.capture());
        assertThat(messages.getAllValues())
                .extracting(message -> message.get("tool_call_id"))
                .containsExactly(null, "call-read");
    }

    @Test
    void interruptsRemainingCallsWhenCancellationArrivesMidBatch() throws Exception {
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
        when(delegate.execute(second)).thenAnswer(invocation -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return LabexNativeToolBatchExecutor.CallExecution.completed(ToolResult.ok("never"));
        });

        LabexNativeToolBatchExecutor.BatchResult result = executor.execute(
                request(List.of(first, second), cancelled::get),
                delegate,
                (admission, toolResult, kind) -> kind.name() + ":" + admission.call().toolCallId());

        assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.CANCELLED);
        verify(journal).interrupted(any(ExecutionFence.class), eq(71L), eq("call-second"), eq("list_files"),
                any(), eq(3), org.mockito.ArgumentMatchers.contains("cancelled"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> messages = ArgumentCaptor.forClass(Map.class);
        verify(transcript, org.mockito.Mockito.times(3)).append(any(ExecutionFence.class), eq(71L), eq(4L), messages.capture());
        assertThat(messages.getAllValues())
                .extracting(message -> message.get("tool_call_id"))
                .containsExactly(null, "call-first", "call-second");
    }

    @Test
    void settlesAlreadyFinishedRemainingCallsWithTheirRealOutcome() throws Exception {
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
        CountDownLatch secondFinished = new CountDownLatch(1);
        // 第二个工具在第一个返回审批之前就已并发完成：结算时必须按真实结果入账，而非伪造成 skipped。
        when(delegate.execute(first)).thenAnswer(invocation -> {
            assertThat(secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
            return LabexNativeToolBatchExecutor.CallExecution.completed(approval);
        });
        when(delegate.execute(second)).thenAnswer(invocation -> {
            secondFinished.countDown();
            return LabexNativeToolBatchExecutor.CallExecution.completed(ToolResult.ok("real-result"));
        });

        LabexNativeToolBatchExecutor.BatchResult result = executor.execute(
                request(List.of(first, second), () -> false),
                delegate,
                (admission, toolResult, kind) -> kind.name() + ":" + admission.call().toolCallId());

        assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.WAITING_APPROVAL);
        assertThat(result.outcomes())
                .extracting(outcome -> outcome.admission().call().toolCallId() + ":" + outcome.status())
                .containsExactly("call-install:WAITING_APPROVAL", "call-read:COMPLETED");
        verify(journal, never()).skipped(any(ExecutionFence.class), eq(71L), eq("call-read"), eq("read_file"),
                any(com.google.gson.JsonObject.class), eq(3), org.mockito.ArgumentMatchers.anyString());
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