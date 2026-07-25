package com.labex.labexagent.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

class StudentAgentControllerCancellationTest {

    @Test
    void interruptMarksAnOwnedActiveTaskAsCancelling() {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        registry.register("session-5", 7, 12, 49L);
        AgentTaskService taskService = mock(AgentTaskService.class);
        StudentAgentController controller = controller(registry, taskService);

        Result<Void> result = controller.interrupt(12, Map.of("sessionId", "session-5"), authentication(7));

        assertTrue(result.isSuccess());
        verify(taskService).requestCancellation(eq(49L), any(), any());
        verify(taskService, never()).cancelScheduledRetry(any(), any(), any());
    }

    @Test
    void interruptWithoutAnActiveSessionCancelsOnlyTheOwnedPersistedRetry() {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentTaskService taskService = mock(AgentTaskService.class);
        StudentAgentController controller = controller(registry, taskService);

        Result<Void> result = controller.interrupt(12, Map.of("taskId", "49"), authentication(7));

        assertTrue(result.isSuccess());
        verify(taskService).cancelScheduledRetry(7, 12, 49L);
        verify(taskService, never()).requestCancellation(any(), any(), any());
    }

    @Test
    void interruptWithoutAValidTaskIdDoesNotAttemptPersistedCancellation() {
        AgentTaskService taskService = mock(AgentTaskService.class);
        StudentAgentController controller = controller(new AgentCancellationRegistry(), taskService);

        Result<Void> result = controller.interrupt(12, Map.of("taskId", "not-a-number"), authentication(7));

        assertTrue(result.isSuccess());
        verify(taskService, never()).cancelScheduledRetry(any(), any(), any());
    }

    @Test
    void interruptCannotCancelAnotherUsersActiveTask() {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun run = registry.register("session-6", 7, 12, 50L);
        AgentTaskService taskService = mock(AgentTaskService.class);
        StudentAgentController controller = controller(registry, taskService);

        Result<Void> result = controller.interrupt(12, Map.of("sessionId", "session-6"), authentication(8));

        assertFalse(result.isSuccess());
        assertFalse(run.isCancellationRequested());
        verify(taskService, never()).updateTask(any(), any(), any(), any());
    }

    private StudentAgentController controller(AgentCancellationRegistry registry, AgentTaskService taskService) {
        return new StudentAgentController(
                mock(AgentLoopEngine.class),
                registry,
                mock(DiffService.class),
                mock(AgentCommandService.class),
                mock(AgentConversationService.class),
                taskService,
                mock(TokenTracker.class),
                mock(PermissionService.class),
                mock(AgentInteractionService.class));
    }

    private Authentication authentication(int studentId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn(String.valueOf(studentId));
        return authentication;
    }
}
