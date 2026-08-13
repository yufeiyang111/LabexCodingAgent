package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.labex.entity.AgentRunInteraction;
import com.labex.mapper.AgentRunInteractionMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

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
    void returnsThePersistedDecisionForAnIdempotentReplay() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction approved = waitingInteraction();
        approved.setInteractionType("permission");
        approved.setStatus("approved");
        when(mapper.selectById("request-71")).thenReturn(approved);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction replayed = service.respond(7, 12, "request-71", "approved", Map.of("action", "allow_once"));

        assertEquals(approved, replayed);
        verify(mapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void rejectsAContradictoryDecisionAfterTheInteractionWasResolved() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction approved = waitingInteraction();
        approved.setInteractionType("permission");
        approved.setStatus("approved");
        when(mapper.selectById("request-71")).thenReturn(approved);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> service.respond(7, 12, "request-71", "rejected", Map.of("action", "reject")));
        verify(mapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void expiresALateDecisionInsideTheResponseTransaction() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction expired = waitingInteraction();
        expired.setInteractionType("permission");
        expired.setExpiresTime(LocalDateTime.now().minusSeconds(1));
        when(mapper.selectById("request-71")).thenReturn(expired);
        when(mapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> service.respond(7, 12, "request-71", "approved", Map.of("action", "allow_once")));
        assertEquals("timed_out", expired.getStatus());
        verify(mapper, times(1)).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void rejectsAnInvalidResolutionStatusAtThePersistenceBoundary() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        when(mapper.selectById("request-71")).thenReturn(waitingInteraction());
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> service.respond(7, 12, "request-71", "running", Map.of()));
        verify(mapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
    }
    @Test
    void findsTheLatestWaitingInteractionForTask() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction waiting = waitingInteraction();
        when(mapper.selectOne(any())).thenReturn(waiting);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction found = service.findWaitingForTask(71L);

        assertEquals(waiting, found);
        verify(mapper).selectOne(any());
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

    @Test
    void matchesApprovedNetworkGrantByDigestAndRequestKind() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction approved = new AgentRunInteraction();
        approved.setTaskId(71L);
        approved.setStatus("approved");
        approved.setInteractionType("network");
        approved.setRequestPayload("{\"requestDigest\":\"digest-1\",\"requestKind\":\"offline_failure_retry\"}");
        approved.setExpiresTime(LocalDateTime.now().plusMinutes(5));
        when(mapper.selectList(any())).thenReturn(java.util.List.of(approved));
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        org.junit.jupiter.api.Assertions.assertTrue(
                service.hasApprovedNetworkGrant(71L, "digest-1", "offline_failure_retry"));
        org.junit.jupiter.api.Assertions.assertFalse(
                service.hasApprovedNetworkGrant(71L, "digest-1", "explicit_command"));
    }

    @Test
    void claimsAnApprovedNetworkRetryAsExecuting() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction approved = waitingInteraction();
        approved.setInteractionType("network");
        approved.setStatus("approved");
        when(mapper.selectById("request-71")).thenReturn(approved);
        when(mapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteractionService.NetworkRetryClaim claim =
                service.claimApprovedNetworkRetry(7, 12, "request-71");

        org.junit.jupiter.api.Assertions.assertTrue(claim.claimed());
        assertEquals("executing", claim.interaction().getStatus());
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void acceptsDuplicateApprovedResponseAfterNetworkRetryWasClaimed() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction executing = waitingInteraction();
        executing.setInteractionType("network");
        executing.setStatus("executing");
        when(mapper.selectById("request-71")).thenReturn(executing);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction replayed = service.respond(
                7, 12, "request-71", "approved", Map.of("action", "allow_once"));

        assertEquals(executing, replayed);
        verify(mapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void completesAnExecutingNetworkRetryIdempotently() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction consumed = waitingInteraction();
        consumed.setInteractionType("network");
        consumed.setStatus("consumed");
        when(mapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(mapper.selectById("request-71")).thenReturn(consumed);
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction completed = service.completeClaimedNetworkRetry(
                7, 12, "request-71", Map.of("executionStatus", "completed"));

        assertEquals("consumed", completed.getStatus());
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void concurrentIdempotentCreateReturnsTheWinningPersistedInteraction() {
        AgentRunInteractionMapper mapper = mock(AgentRunInteractionMapper.class);
        AgentRunInteraction winner = waitingInteraction();
        winner.setInteractionId("request-winner-71");
        when(mapper.selectById("request-71")).thenReturn(null);
        when(mapper.selectOne(any())).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("duplicate idempotency key"))
                .when(mapper).insert(any(AgentRunInteraction.class));
        AgentRunInteractionService service = new AgentRunInteractionService(mapper);

        AgentRunInteraction interaction = service.createWaiting(
                new AgentRunInteractionService.WaitingInteraction(
                        "request-71", 71L, "conversation-1", "session-1", 7, 12,
                        "network", Map.of("request", "mvn test"), "network-request-71",
                        LocalDateTime.now().plusMinutes(10)));

        assertEquals("request-winner-71", interaction.getInteractionId());
    }

    private AgentRunInteraction waitingInteraction() {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId("request-71");
        interaction.setTaskId(71L);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        interaction.setInteractionType("question");
        interaction.setStatus("waiting");
        return interaction;
    }
}
