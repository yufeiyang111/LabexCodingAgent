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
import org.mockito.ArgumentCaptor;

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
        ArgumentCaptor<java.util.Map<String, Object>> payload = ArgumentCaptor.forClass(java.util.Map.class);
        verify(lifecycle).transitionIfCurrent(
                eq(71L), eq(AgentRunState.WAITING_USER), eq(AgentRunState.FAILED),
                eq("RUN_INTERACTION_TIMED_OUT"), payload.capture(), eq("Interaction timed out"),
                eq("Timed out while waiting for user input"), eq("interaction-timeout-" + question.getInteractionId()));
        assertEquals("2026-07-23T12:00", payload.getValue().get("timedOutAt"));
        assertEquals("2026-07-23T11:59", payload.getValue().get("expiresTime"));
    }

    @Test
    void doesNotFailTheTaskForExpiredPermissionApprovals() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunInteraction permission = interaction("permission", 72L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 12, 0);
        when(interactions.claimExpired(now, 100)).thenReturn(List.of(permission));
        AgentRunInteractionTimeoutService service = new AgentRunInteractionTimeoutService(interactions, lifecycle);

        service.expireAvailable(now);

        // 权限审批过期走温和恢复：任务不失败，恢复调度（selectResolvedAwaitingResume 含 timed_out）接管。
        verify(lifecycle, org.mockito.Mockito.never()).transitionIfCurrent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotFailTheTaskForExpiredNetworkApprovals() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunInteraction network = interaction("network", 73L);
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 12, 0);
        when(interactions.claimExpired(now, 100)).thenReturn(List.of(network));
        AgentRunInteractionTimeoutService service = new AgentRunInteractionTimeoutService(interactions, lifecycle);

        service.expireAvailable(now);

        verify(lifecycle, org.mockito.Mockito.never()).transitionIfCurrent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotFailTheTaskForExpiredConfigProposalInteractions() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunInteraction configProposal = interaction(AgentRunInteraction.TYPE_CONFIG_PROPOSAL, 74L);
        configProposal.setStatus("timed_out");
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 12, 0);
        when(interactions.claimExpired(now, 100)).thenReturn(List.of(configProposal));
        AgentRunInteractionTimeoutService service = new AgentRunInteractionTimeoutService(interactions, lifecycle);

        service.expireAvailable(now);

        // config_proposal 与 permission/network 一致走温和恢复：任务不失败；
        // claimExpired 已把交互置为 timed_out，决策路径的 claim 谓词接受 timed_out，
        // 恢复后模型收到"已过期、视为未批准"的提示重新评估（见 AgentRunConfigProposalInteractionTest）。
        assertEquals("timed_out", configProposal.getStatus());
        verify(lifecycle, org.mockito.Mockito.never()).transitionIfCurrent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private AgentRunInteraction interaction(String type, Long taskId) {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId(type + "-1");
        interaction.setTaskId(taskId);
        interaction.setInteractionType(type);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        interaction.setExpiresTime(LocalDateTime.of(2026, 7, 23, 11, 59));
        return interaction;
    }
}
