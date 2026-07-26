package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRunResumeSchedulerTest {

    @Test
    void resumesAnAnsweredQuestionOnlyWhileItsTaskIsWaiting() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = task(71L, "waiting_user");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(questionAnswer(71L));

        ArgumentCaptor<AgentStreamRequest> request = ArgumentCaptor.forClass(AgentStreamRequest.class);
        assertThat(scheduled).isTrue();
        verify(tasks).updateTask(eq(71L), eq("running"), eq("Resuming after user response"), eq("A persisted user response is ready"));
        verify(engine).resume(eq(7), eq(12), request.capture(), eq(71L), eq(true));
        assertThat(request.getValue().getConversationId()).isEqualTo("conversation-1");
        assertThat(request.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(request.getValue().getResumeTaskId()).isEqualTo(71L);
        assertThat(request.getValue().getMessage()).contains("answered").contains("Continue the existing task");
    }

    @Test
    void resumesAQuestionThatWasSkippedSoTheAgentCanFinishSafely() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(interaction(71L, "question", "cancelled"));

        assertThat(scheduled).isTrue();
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class), eq(71L), eq(true));
    }

    @Test
    void marksTheTaskFailedWhenAResolvedInteractionCannotBeQueued() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"));
        doThrow(new IllegalStateException("queue full")).when(engine)
                .resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class), eq(71L), eq(true));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(questionAnswer(71L));

        assertThat(scheduled).isFalse();
        verify(tasks).updateTask(71L, "failed", "Unable to resume after user response", "queue full");
    }

    @Test
    void resumesAnApprovedPermissionOnlyWhileItsTaskIsWaiting() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_approval"));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(interaction(71L, "permission", "approved"));

        assertThat(scheduled).isTrue();
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class), eq(71L), eq(true));
    }

    private AgentTask task(Long taskId, String status) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setSessionId("session-1");
        task.setMode("build");
        task.setStatus(status);
        return task;
    }

    private AgentRunInteraction questionAnswer(Long taskId) {
        return interaction(taskId, "question", "answered");
    }

    private AgentRunInteraction interaction(Long taskId, String type, String status) {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setTaskId(taskId);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        interaction.setInteractionType(type);
        interaction.setStatus(status);
        interaction.setRequestPayload("{\"question\":\"Continue?\"}");
        interaction.setResponsePayload("{\"answer\":\"yes\"}");
        return interaction;
    }
}
