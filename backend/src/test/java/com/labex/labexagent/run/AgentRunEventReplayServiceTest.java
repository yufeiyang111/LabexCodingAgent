package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunEventReplayServiceTest {

    @Test
    void returnsOnlyEventsForAnOwnedRunAfterTheRequestedSequence() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        when(taskMapper.selectById(71L)).thenReturn(task);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(5L);
        event.setEventType("THINK");
        when(eventMapper.selectList(any())).thenReturn(List.of(event));

        AgentRunEventReplayService service = new AgentRunEventReplayService(taskMapper, eventMapper);
        List<AgentRunEvent> events = service.eventsAfter(7, 12, 71L, 4L);

        assertEquals(List.of(event), events);
        verify(eventMapper).selectList(any());
    }

    @Test
    void normalizesLegacyReasoningPayloadsWhileReplayingWithoutRewritingHistory() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        when(taskMapper.selectById(71L)).thenReturn(task);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(5L);
        event.setEventType("FINAL");
        event.setPayload("{\"content\":\"Visible &lt;THINK&gt;private&lt;/THINKING&gt; answer\"}");
        when(eventMapper.selectList(any())).thenReturn(List.of(event));

        AgentRunEventReplayService service = new AgentRunEventReplayService(taskMapper, eventMapper);
        List<AgentRunEvent> events = service.eventsAfter(7, 12, 71L, 4L);

        assertEquals("{\"content\":\"Visible  answer\"}", events.get(0).getPayload());
        verify(eventMapper, never()).updateById(any(AgentRunEvent.class));
    }

    @Test
    void rejectsEventsForAnotherRunOwnerBeforeQueryingTheEventLog() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(8);
        task.setProjectId(12);
        when(taskMapper.selectById(71L)).thenReturn(task);

        AgentRunEventReplayService service = new AgentRunEventReplayService(taskMapper, eventMapper);

        assertThrows(IllegalArgumentException.class, () -> service.eventsAfter(7, 12, 71L, 0L));
        verify(eventMapper, never()).selectList(any());
    }
}
