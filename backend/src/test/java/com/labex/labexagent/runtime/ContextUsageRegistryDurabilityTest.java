package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextUsageRegistryDurabilityTest {
    @Test
    void rebuildsLastContextStatusFromDurableRunEventAfterMemoryIsEmpty() {
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentTask task = new AgentTask();
        task.setTaskId(91L);
        task.setConversationId("conversation-91");
        task.setStudentId(7);
        task.setProjectId(12);
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(91L);
        event.setEventType("CONTEXT_STATUS");
        event.setPayload("{\"conversationId\":\"conversation-91\",\"sessionId\":\"session-91\",\"provider\":\"acceptance\",\"model\":\"model-91\",\"contextWindowTokens\":1000,\"categories\":{\"messages\":120},\"trimState\":\"NONE\"}");
        when(tasks.selectList(any())).thenReturn(List.of(task));
        when(events.selectList(any())).thenReturn(List.of(event));

        ContextUsageRegistry registry = new ContextUsageRegistry(events, tasks);

        assertThat(registry.find("conversation-91"))
                .get()
                .extracting(ContextUsageSnapshot::toPayload)
                .asString()
                .contains("conversation-91", "model-91", "messages");
    }
}
