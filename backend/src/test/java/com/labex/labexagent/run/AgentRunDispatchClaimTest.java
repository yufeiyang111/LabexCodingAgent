package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunOutbox;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunDispatchClaimTest {

    @Test
    void claimsStateAndExecutionLeaseInOneDurableDispatchOperation() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.WAITING_USER);
        task.setRunVersion(4L);
        task.setExecutionEpoch(4L);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(900L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunLifecycleService.DispatchClaim claim = service.claimDispatch(
                71L,
                AgentRunState.WAITING_USER,
                AgentRunState.RECOVERING,
                "RUN_INTERACTION_RESUME_QUEUED",
                Map.of("interactionId", "interaction-71"),
                "Resuming after user response",
                "A persisted user response is ready",
                "interaction-resume-71",
                "instance-a",
                30_000L);

        assertNotNull(claim);
        assertEquals("instance-a", claim.lease().owner());
        assertEquals(5L, claim.lease().epoch());
        assertEquals("recovering", task.getStatus());
        assertEquals("instance-a", task.getExecutionOwner());
        assertNotNull(task.getExecutionLeaseExpiresAt());
        verify(outboxMapper).insert(any(AgentRunOutbox.class));
    }

    @Test
    void doesNotClaimWhenAnotherWorkerLeaseIsStillActive() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.WAITING_USER);
        task.setExecutionOwner("instance-old");
        task.setExecutionLeaseExpiresAt(LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunLifecycleService.DispatchClaim claim = service.claimDispatch(
                71L,
                AgentRunState.WAITING_USER,
                AgentRunState.RECOVERING,
                "RUN_INTERACTION_RESUME_QUEUED",
                Map.of(),
                "Resuming",
                "Resume",
                "interaction-resume-71",
                "instance-new",
                30_000L);

        assertNull(claim);
        verify(taskMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
    }

    private AgentTask task(AgentRunState state) {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setStatus(state.persistedStatus());
        task.setRunVersion(0L);
        task.setLastEventSequence(0L);
        return task;
    }
}
