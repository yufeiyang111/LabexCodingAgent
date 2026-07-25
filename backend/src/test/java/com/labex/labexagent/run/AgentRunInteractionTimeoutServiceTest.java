package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunInteractionTimeoutServiceTest {

    @Test
    void expiresAQuestionAndFailsOnlyTheStillWaitingRun() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunInteraction question = interaction("question", 71L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 12, 0);
        when(interactions.claimExpired(now, 100)).thenReturn(List.of(question));
        when(lifecycle.transitionIfCurrent(eq(71L), eq(AgentRunState.WAITING_USER), eq(AgentRunState.FAILED),
                any(), any(), any(), any(), any())).thenReturn(true);
        AgentRunInteractionTimeoutService service = new AgentRunInteractionTimeoutService(interactions, lifecycle);

        int expired = service.expireAvailable(now);

        assertEquals(1, expired);
        verify(lifecycle).transitionIfCurrent(
                eq(71L), eq(AgentRunState.WAITING_USER), eq(AgentRunState.FAILED),
                eq("RUN_INTERACTION_TIMED_OUT"), any(), eq("Interaction timed out"),
                eq("Timed out while waiting for user input"), eq("interaction-timeout-" + question.getInteractionId()));
    }

    @Test
    void usesApprovalWaitingStateForExpiredPermission() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunInteraction permission = interaction("permission", 72L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 12, 0);
        when(interactions.claimExpired(now, 100)).thenReturn(List.of(permission));
        AgentRunInteractionTimeoutService service = new AgentRunInteractionTimeoutService(interactions, lifecycle);

        service.expireAvailable(now);

        verify(lifecycle).transitionIfCurrent(eq(72L), eq(AgentRunState.WAITING_APPROVAL), eq(AgentRunState.FAILED),
                any(), any(), any(), any(), any());
    }

    private AgentRunInteraction interaction(String type, Long taskId) {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId(type + "-1");
        interaction.setTaskId(taskId);
        interaction.setInteractionType(type);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        return interaction;
    }
}
