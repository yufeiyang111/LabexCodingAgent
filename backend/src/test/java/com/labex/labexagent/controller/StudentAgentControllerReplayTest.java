package com.labex.labexagent.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.run.AgentRunEventReplayService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentCommandService;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class StudentAgentControllerReplayTest {

    @Test
    void replaysOnlyEventsAfterTheLastEventId() {
        AgentRunEventReplayService replayService = mock(AgentRunEventReplayService.class);
        when(replayService.eventsAfter(7, 12, 71L, 41L)).thenReturn(List.of());
        StudentAgentController controller = new StudentAgentController(
                mock(AgentLoopEngine.class),
                mock(AgentCancellationRegistry.class),
                mock(DiffService.class),
                mock(AgentCommandService.class),
                mock(AgentConversationService.class),
                mock(AgentTaskService.class),
                mock(TokenTracker.class),
                mock(PermissionService.class),
                mock(AgentInteractionService.class),
                replayService);

        controller.replayEvents(12, 71L, "41", null, authentication(7));

        verify(replayService).eventsAfter(eq(7), eq(12), eq(71L), eq(41L));
    }

    private Authentication authentication(int studentId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(String.valueOf(studentId));
        return authentication;
    }
}
