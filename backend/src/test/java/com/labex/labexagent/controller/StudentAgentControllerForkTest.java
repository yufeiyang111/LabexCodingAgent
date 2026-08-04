package com.labex.labexagent.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
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

class StudentAgentControllerForkTest {

    @Test
    void acceptsDurableTaskIdAndLegacyMessageIdTogether() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentConversation child = new AgentConversation();
        child.setConversationId("child");
        when(conversations.forkConversation(7, 3, "source", 88L, 11L)).thenReturn(child);
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class),
                mock(AgentCancellationRegistry.class),
                mock(DiffService.class),
                mock(AgentCommandService.class),
                conversations,
                mock(AgentTaskService.class),
                mock(TokenTracker.class),
                mock(PermissionService.class),
                mock(AgentInteractionService.class));

        controller.forkConversation(3, "source",
                Map.of("messageId", 88, "taskId", "11"), authentication(7));

        verify(conversations).forkConversation(7, 3, "source", 88L, 11L);
    }

    private Authentication authentication(int studentId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(String.valueOf(studentId));
        return authentication;
    }
}