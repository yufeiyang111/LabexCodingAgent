package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentTask;
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