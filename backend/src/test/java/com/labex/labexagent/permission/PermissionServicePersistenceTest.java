package com.labex.labexagent.permission;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunResumeScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PermissionServicePersistenceTest {

    @Test
    void persistsTaskScopedPermissionRequestsAndReplies() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        PermissionService service = new PermissionService(mock(JdbcTemplate.class), interactions);

        PermissionApprovalRequest request = service.beginApproval(
                12, 7, 71L, "conversation-1", "session-1", "shell", "npm test", "Run tests", "shell", "npm test");
        PermissionService.PermissionApprovalResult result = service.reply(
                12, 7, request.getRequestId(), "allow_once", "");

        assertTrue(result.isGranted());
        verify(interactions).createWaiting(any(AgentRunInteractionService.WaitingInteraction.class));
        verify(interactions).respond(eq(7), eq(12), eq(request.getRequestId()), eq("approved"), any());
    }

    @Test
    void resumesAPersistedApprovalEvenWhenTheOriginalRequestIsStillInMemory() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunResumeScheduler scheduler = mock(AgentRunResumeScheduler.class);
        AgentRunInteraction persisted = new AgentRunInteraction();
        persisted.setInteractionType("permission");
        persisted.setStatus("approved");
        when(interactions.respond(eq(7), eq(12), any(), eq("approved"), any())).thenReturn(persisted);
        PermissionService service = new PermissionService(mock(JdbcTemplate.class), interactions, scheduler);
        PermissionApprovalRequest request = service.beginApproval(
                12, 7, 71L, "conversation-1", "session-1", "shell", "npm test", "Run tests", "shell", "npm test");

        PermissionService.PermissionApprovalResult result = service.reply(
                12, 7, request.getRequestId(), "allow_once", "");

        assertTrue(result.isGranted());
        verify(scheduler).resumeIfWaiting(persisted);
    }

    @Test
    void resumesAPersistedApprovalWhenTheOriginalInMemoryWaiterIsGone() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunResumeScheduler scheduler = mock(AgentRunResumeScheduler.class);
        AgentRunInteraction persisted = new AgentRunInteraction();
        persisted.setInteractionType("permission");
        persisted.setStatus("approved");
        when(interactions.respond(eq(7), eq(12), eq("approval-71"), eq("approved"), any())).thenReturn(persisted);
        PermissionService service = new PermissionService(mock(JdbcTemplate.class), interactions, scheduler);

        PermissionService.PermissionApprovalResult result = service.reply(
                12, 7, "approval-71", "allow_once", "");

        assertTrue(result.isGranted());
        verify(scheduler).resumeIfWaiting(persisted);
    }
}
