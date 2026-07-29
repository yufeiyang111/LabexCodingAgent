package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
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

        AgentRunPart result = new AgentRunPartService(parts, tasks)
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

        AgentRunPart result = new AgentRunPartService(parts, tasks)
                .upsertToolCall(7L, "call-1", "completed", "run_tests",
                        Map.of("strategy", "test"), 3, "passed");

        assertThat(result).isSameAs(existing);
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getOutputText()).isEqualTo("passed");
        verify(parts).updateById(existing);
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