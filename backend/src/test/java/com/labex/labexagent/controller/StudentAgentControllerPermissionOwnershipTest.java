package com.labex.labexagent.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.common.Result;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentCommandService;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class StudentAgentControllerPermissionOwnershipTest {

    @Test
    void passesTheAuthenticatedUserToPermissionReplies() {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.reply(12, 7, "request-71", "allow_once", ""))
                .thenReturn(new PermissionService.PermissionApprovalResult(true, false, null));
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class), mock(AgentCancellationRegistry.class), mock(DiffService.class),
                mock(AgentCommandService.class), mock(AgentConversationService.class), mock(AgentTaskService.class),
                mock(TokenTracker.class), permissionService, mock(AgentInteractionService.class));

        Result<Map<String, Object>> result = controller.approvePermission(
                12, Map.of("requestId", "request-71", "action", "allow_once", "feedback", ""), authentication(7));

        assertTrue(result.isSuccess());
        verify(permissionService).reply(eq(12), eq(7), eq("request-71"), eq("allow_once"), eq(""));
    }

    @Test
    void refusesTokenStatsForAConversationOutsideTheAuthenticatedProjectScope() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        TokenTracker tracker = mock(TokenTracker.class);
        when(conversations.getOwnedConversation(7, 12, "conversation-foreign")).thenReturn(null);
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class), mock(AgentCancellationRegistry.class), mock(DiffService.class),
                mock(AgentCommandService.class), conversations, mock(AgentTaskService.class),
                tracker, mock(PermissionService.class), mock(AgentInteractionService.class));

        Result<Map<String, Object>> result = controller.tokenStats(12, "conversation-foreign", authentication(7));

        assertTrue(!result.isSuccess());
        verify(tracker, never()).getConversationStats("conversation-foreign", 7, 12);
    }

    private Authentication authentication(int studentId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(String.valueOf(studentId));
        return authentication;
    }
}
