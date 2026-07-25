package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.mapper.AgentRunInteractionMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunInteractionServiceTest {

    @Test
    void persistsAWaitingInteractionWithTheSuppliedIdempotencyKey() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        when(mapper.selectById("request-71")).thenReturn(null);
        when(mapper.insert(any(AgentRunInteraction.class))).thenReturn(1);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction interaction = service.createWaiting(new AgentRunInteractionService.WaitingInteraction(
                "request-71", 71L, "conversation-1", "session-1", 7, 12,
                "question", Map.of("question", "Continue?"), "question-request-71",
                LocalDateTime.now().plusMinutes(10)));

        assertEquals("waiting", interaction.getStatus());
        assertEquals("question", interaction.getInteractionType());
        assertEquals("question-request-71", interaction.getIdempotencyKey());
        verify(mapper).insert(interaction);
    }

    @Test
    void returnsAnExistingInteractionForTheSameRequestId() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction existing = new AgentRunInteraction();
        existing.setInteractionId("request-71");
        when(mapper.selectById("request-71")).thenReturn(existing);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction interaction = service.createWaiting(new AgentRunInteractionService.WaitingInteraction(
                "request-71", 71L, "conversation-1", "session-1", 7, 12,
                "question", Map.of(), "question-request-71", LocalDateTime.now().plusMinutes(10)));

        assertEquals(existing, interaction);
        verify(mapper, never()).insert(any(AgentRunInteraction.class));
    }

    @Test
    void recordsAnOwnedResponseExactlyOnce() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction interaction = waitingInteraction();
        when(mapper.selectById("request-71")).thenReturn(interaction);
        when(mapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction responded = service.respond(7, 12, "request-71", "answered", Map.of("answer", "yes"));

        assertEquals("answered", responded.getStatus());
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void claimsAnExpiredWaitingInteractionExactlyOnce() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction waiting = waitingInteraction();
        waiting.setExpiresTime(LocalDateTime.of(2026, 7, 23, 10, 0));
        when(mapper.selectList(any())).thenReturn(java.util.List.of(waiting));
        when(mapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        java.util.List<AgentRunInteraction> claimed = service.claimExpired(LocalDateTime.of(2026, 7, 23, 10, 1), 10);

        assertEquals(1, claimed.size());
        assertEquals("timed_out", claimed.get(0).getStatus());
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void rejectsResponsesFromAnotherUser() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        when(mapper.selectById("request-71")).thenReturn(waitingInteraction());
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> service.respond(8, 12, "request-71", "answered", Map.of("answer", "yes")));
        verify(mapper, never()).updateById(any(AgentRunInteraction.class));
    }

    private AgentRunInteraction waitingInteraction() {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId("request-71");
        interaction.setTaskId(71L);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        interaction.setStatus("waiting");
        return interaction;
    }
}
