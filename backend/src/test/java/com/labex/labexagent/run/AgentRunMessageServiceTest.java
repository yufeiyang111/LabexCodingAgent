package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunExecutionLeaseService.StaleExecutionFenceException;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunMessageServiceTest {

    @Test
    void groupsToolPartsUnderAStableAssistantTurnMessage() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(messages.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(41L);
            return 1;
        });

        AgentRunMessage message = new AgentRunMessageService(messages, tasks)
                .upsertAssistantTurn(7L, 3, "streaming");

        assertThat(message.getRunMessageId()).isEqualTo(41L);
        assertThat(message.getMessageKey()).isEqualTo("assistant:turn:3");
        assertThat(message.getConversationId()).isEqualTo("conversation-7");
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getStatus()).isEqualTo("streaming");
    }

    @Test
    void finalEventCompletesOneStableFinalMessage() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessage existing = new AgentRunMessage();
        existing.setRunMessageId(42L);
        existing.setMessageKey("assistant:final");
        when(messages.selectOne(any())).thenReturn(existing);
        when(tasks.selectById(7L)).thenReturn(task());

        AgentRunMessage result = new AgentRunMessageService(messages, tasks)
                .recordEventMessage(7L, "FINAL", Map.of("content", "done"), 19L);

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getContent()).isEqualTo("done");
        verify(messages).updateById(existing);
    }

    @Test
    void persistsLongFinalAnswersWithoutSilentTruncation() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessage existing = new AgentRunMessage();
        existing.setRunMessageId(42L);
        existing.setMessageKey("assistant:final");
        when(messages.selectOne(any())).thenReturn(existing);
        when(tasks.selectById(7L)).thenReturn(task());

        String longAnswer = "a".repeat(20_000);
        AgentRunMessage result = new AgentRunMessageService(messages, tasks)
                .recordEventMessage(7L, "FINAL", Map.of("content", longAnswer), 19L);

        assertThat(result.getContent()).hasSize(20_000).doesNotContain("...truncated...");
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setConversationId("conversation-7");
        task.setStudentId(11);
        task.setProjectId(22);
        return task;
    }

    @Test
    void finalEventPersistsOnlyVisibleContentAtTheDurableMessageBoundary() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(messages.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(43L);
            return 1;
        });

        AgentRunMessage result = new AgentRunMessageService(messages, tasks)
                .recordEventMessage(7L, "FINAL", Map.of(
                        "content", "Visible <THINK data-kind='hidden'>private plan</THINKING> answer"), 20L);

        assertThat(result.getContent()).isEqualTo("Visible  answer");
        assertThat(result.getMetadata()).doesNotContainIgnoringCase("<think");
    }


    @Test
    void fencedAssistantTurnRejectsStaleOwnerBeforeWritingMessage() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunMessageService service = new AgentRunMessageService(messages, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.upsertAssistantTurn(new ExecutionFence(7L, "instance-a", 4L),
                        7L, 3, "streaming"));

        assertEquals(StaleExecutionFenceException.Reason.STALE_OWNER, error.reason());
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
    }

    @Test
    void fencedAssistantTurnRejectsStaleEpochBeforeWritingMessage() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunMessageService service = new AgentRunMessageService(messages, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.upsertAssistantTurn(new ExecutionFence(7L, "instance-a", 3L),
                        7L, 3, "streaming"));

        assertEquals(StaleExecutionFenceException.Reason.STALE_EPOCH, error.reason());
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
    }

    @Test
    void fencedAssistantTurnRejectsExpiredLeaseBeforeWritingMessage() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-a", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 9, 59, 59)));

        AgentRunMessageService service = new AgentRunMessageService(messages, tasks);
        StaleExecutionFenceException error = assertThrows(StaleExecutionFenceException.class,
                () -> service.upsertAssistantTurn(new ExecutionFence(7L, "instance-a", 4L),
                        7L, 3, "streaming"));

        assertEquals(StaleExecutionFenceException.Reason.EXPIRED_LEASE, error.reason());
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
    }

    @Test
    void fencedAssistantTurnPersistsMessageWhenTheFenceIsActive() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(messages.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(47L);
            return 1;
        });

        AgentRunMessage message = new AgentRunMessageService(messages, tasks)
                .upsertAssistantTurn(new ExecutionFence(7L, "instance-a", 4L),
                        7L, 3, "streaming");

        assertThat(message.getRunMessageId()).isEqualTo(47L);
        assertThat(message.getMessageKey()).isEqualTo("assistant:turn:3");
        verify(messages).insert(any(AgentRunMessage.class));
    }

    @Test
    void fencedEventMessageRejectsStaleFenceBeforeWritingMessage() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(7L)).thenReturn(task("instance-b", 4L,
                java.time.LocalDateTime.of(2026, 7, 23, 10, 1)));

        AgentRunMessageService service = new AgentRunMessageService(messages, tasks);
        assertThrows(StaleExecutionFenceException.class,
                () -> service.recordEventMessage(new ExecutionFence(7L, "instance-a", 4L),
                        7L, "FINAL", Map.of("content", "done"), 19L));

        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(messages, never()).updateById(any(AgentRunMessage.class));
    }

    private AgentTask task(String owner, long epoch, java.time.LocalDateTime leaseExpiresAt) {
        AgentTask task = new AgentTask();
        task.setTaskId(7L);
        task.setConversationId("conversation-7");
        task.setStudentId(11);
        task.setProjectId(22);
        task.setExecutionOwner(owner);
        task.setExecutionEpoch(epoch);
        task.setExecutionLeaseExpiresAt(leaseExpiresAt);
        return task;
    }

    @Test
    void publicHistorySanitizesLegacyDirtyFinalMessagesWithoutRewritingRows() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessage dirty = new AgentRunMessage();
        dirty.setMessageKey("assistant:final");
        dirty.setContent("Visible <think>private plan</think> answer");
        when(messages.selectList(any())).thenReturn(List.of(dirty));

        List<Map<String, Object>> history = new AgentRunMessageService(messages, tasks).publicHistory(7L);

        assertThat(history).singleElement().extracting(item -> item.get("content"))
                .isEqualTo("Visible  answer");
        assertThat(dirty.getContent()).contains("private plan");
    }

    @Test
    void reasoningEventKeepsReasoningBodyButRemovesPrivateBlocksFromVisibleMetadata() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(messages.selectOne(any())).thenReturn(null);
        when(tasks.selectById(7L)).thenReturn(task());
        when(messages.insert(any(AgentRunMessage.class))).thenAnswer(invocation -> {
            AgentRunMessage message = invocation.getArgument(0);
            message.setRunMessageId(45L);
            return 1;
        });

        AgentRunMessage result = new AgentRunMessageService(messages, tasks)
                .recordEventMessage(7L, "THINK", Map.of(
                        "content", "\\<think\\>reasoning body\\</think\\>",
                        "summary", "\\<thinking\\>private title\\</thinking\\>Visible summary"), 22L);

        assertThat(result.getContent()).isEqualTo("reasoning body");
        assertThat(result.getMetadata()).contains("Visible summary");
        assertThat(result.getMetadata()).doesNotContain("private title");
        assertThat(result.getMetadata()).doesNotContainIgnoringCase("<think");
    }

}