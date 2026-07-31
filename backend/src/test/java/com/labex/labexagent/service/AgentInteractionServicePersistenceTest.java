package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunResumeScheduler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentInteractionServicePersistenceTest {

    @Test
    void persistsAQuestionBeforeExposingItToTheRuntime() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentInteractionService service = new AgentInteractionService(interactions);

        AgentInteractionService.UserQuestionRequest request = service.beginQuestion(
                12, 7, "session-1", 71L, "conversation-1", "tool-call-71",
                "Continue?", "Need input", List.of("yes", "no"));

        ArgumentCaptor<AgentRunInteractionService.WaitingInteraction> captor =
                ArgumentCaptor.forClass(AgentRunInteractionService.WaitingInteraction.class);
        verify(interactions).createWaiting(captor.capture());
        assertEquals("tool-call-71", ((java.util.Map<?, ?>) captor.getValue().requestPayload()).get("toolCallId"));
        assertTrue(request.requestId() != null && !request.requestId().isBlank());
    }

    @Test
    void persistsAnOwnedAnswerBeforeCompletingTheInMemoryWaiter() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentInteractionService service = new AgentInteractionService(interactions);
        AgentInteractionService.UserQuestionRequest request = service.beginQuestion(
                12, 7, "session-1", 71L, "conversation-1", "Continue?", "Need input", List.of());

        AgentInteractionService.UserQuestionResult result = service.reply(
                12, 7, request.requestId(), "answer", "yes");

        assertTrue(result.answered());
        verify(interactions).respond(eq(7), eq(12), eq(request.requestId()), eq("answered"), any());
    }

    @Test
    void schedulesAPersistedAnswerEvenWhenTheOriginalRequestIsStillInMemory() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunResumeScheduler scheduler = mock(AgentRunResumeScheduler.class);
        AgentRunInteraction persisted = new AgentRunInteraction();
        persisted.setInteractionType("question");
        persisted.setStatus("answered");
        when(interactions.respond(eq(7), eq(12), any(), eq("answered"), any())).thenReturn(persisted);
        AgentInteractionService service = new AgentInteractionService(interactions, scheduler);
        AgentInteractionService.UserQuestionRequest request = service.beginQuestion(
                12, 7, "session-1", 71L, "conversation-1", "Continue?", "Need input", List.of());

        AgentInteractionService.UserQuestionResult result = service.reply(
                12, 7, request.requestId(), "answer", "yes");

        assertTrue(result.answered());
        verify(scheduler).resumeIfWaiting(persisted);
    }

    @Test
    void schedulesAPersistedAnswerWhenTheOriginalInMemoryWaiterIsGone() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunResumeScheduler scheduler = mock(AgentRunResumeScheduler.class);
        AgentRunInteraction persisted = new AgentRunInteraction();
        persisted.setInteractionType("question");
        persisted.setStatus("answered");
        when(interactions.respond(eq(7), eq(12), eq("question-71"), eq("answered"), any())).thenReturn(persisted);
        AgentInteractionService service = new AgentInteractionService(interactions, scheduler);

        AgentInteractionService.UserQuestionResult result = service.reply(
                12, 7, "question-71", "answer", "yes");

        assertTrue(result.answered());
        verify(scheduler).resumeIfWaiting(persisted);
    }
}
