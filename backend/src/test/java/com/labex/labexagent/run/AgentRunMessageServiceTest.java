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
}