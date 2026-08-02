package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRunResumeSchedulerTest {

    @Test
    void resumesAnAnsweredQuestionOnlyWhileItsTaskIsWaiting() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask task = task(71L, "waiting_user");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task, task(71L, "recovering"));
        when(tasks.claimInteractionResume(eq(71L), org.mockito.ArgumentMatchers.anyString(), eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease()));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(questionAnswer(71L));

        ArgumentCaptor<AgentStreamRequest> request = ArgumentCaptor.forClass(AgentStreamRequest.class);
        assertThat(scheduled).isTrue();
        verify(tasks).claimInteractionResume(eq(71L), eq("interaction-71-question-answered"), eq("Resuming after user response"), eq("A persisted user response is ready"));
        verify(engine).resume(eq(7), eq(12), request.capture(), eq(71L), eq(true), eq(lease()));
        assertThat(request.getValue().getConversationId()).isEqualTo("conversation-1");
        assertThat(request.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(request.getValue().getResumeTaskId()).isEqualTo(71L);
        assertThat(request.getValue().getResumeInteractionId()).isEqualTo("interaction-71-question-answered");
        assertThat(request.getValue().getMessage()).contains("answered").contains("Continue the existing task");
    }

    @Test
    void retriesAResolvedInteractionAfterThePreviousWorkerReleasesItsLease() throws Exception {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask waiting = task(71L, "waiting_user");
        when(tasks.getOwnedTask(7, 12, 71L))
                .thenReturn(waiting, waiting, task(71L, "recovering"));
        when(tasks.claimInteractionResume(
                eq(71L),
                eq("interaction-71-question-answered"),
                eq("Resuming after user response"),
                eq("A persisted user response is ready")))
                .thenReturn(null, new AgentRunLifecycleService.DispatchClaim(lease()));
        CountDownLatch resumed = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            resumed.countDown();
            return null;
        }).when(engine).resume(
                eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean accepted = scheduler.resumeIfWaiting(questionAnswer(71L));

        assertThat(accepted).isTrue();
        assertThat(resumed.await(2, TimeUnit.SECONDS)).isTrue();
        verify(tasks, org.mockito.Mockito.times(2)).claimInteractionResume(
                eq(71L),
                eq("interaction-71-question-answered"),
                eq("Resuming after user response"),
                eq("A persisted user response is ready"));
        verify(engine).resume(
                eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()));
    }
    @Test
    void reconcilesAPersistedResponseAfterProcessRestart() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunInteraction answered = questionAnswer(71L);
        when(interactions.findResolvedAwaitingResume(100)).thenReturn(java.util.List.of(answered));
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"), task(71L, "recovering"));
        when(tasks.claimInteractionResume(
                eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease()));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine, interactions);

        int resumed = scheduler.resumeResolvedInteractions();

        assertThat(resumed).isEqualTo(1);
        verify(engine).resume(
                eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()));
    }
    @Test
    void doesNotEnqueueWhenThePersistedTaskDidNotEnterRecovery() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask waiting = task(71L, "waiting_user");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(waiting, task(71L, "waiting_user"));
        when(tasks.claimInteractionResume(eq(71L), org.mockito.ArgumentMatchers.anyString(), eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease()));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(questionAnswer(71L));

        assertThat(scheduled).isFalse();
        verify(engine, never()).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class), eq(71L), eq(true), eq(lease()));
    }

    @Test
    void resumesAQuestionThatWasSkippedSoTheAgentCanFinishSafely() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"), task(71L, "recovering"));
        when(tasks.claimInteractionResume(eq(71L), org.mockito.ArgumentMatchers.anyString(), eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease()));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(interaction(71L, "question", "cancelled"));

        assertThat(scheduled).isTrue();
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class), eq(71L), eq(true), eq(lease()));
    }

    @Test
    void marksTheTaskFailedWhenAResolvedInteractionCannotBeQueued() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"), task(71L, "recovering"));
        when(tasks.claimInteractionResume(eq(71L), org.mockito.ArgumentMatchers.anyString(), eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease()));
        doThrow(new IllegalStateException("queue full")).when(engine)
                .resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class), eq(71L), eq(true), eq(lease()));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(questionAnswer(71L));

        assertThat(scheduled).isFalse();
        verify(tasks).updateTask(71L, "failed", "Unable to resume after user response", "queue full",
                "interaction-resume-dispatch-interaction-71-question-answered");
    }

    @Test
    void resumesAnApprovedPermissionOnlyWhileItsTaskIsWaiting() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_approval"), task(71L, "recovering"));
        when(tasks.claimInteractionResume(eq(71L), org.mockito.ArgumentMatchers.anyString(), eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease()));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(interaction(71L, "permission", "approved"));

        assertThat(scheduled).isTrue();
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class), eq(71L), eq(true), eq(lease()));
    }

    private AgentRunExecutionLeaseService.ExecutionLease lease() {
        return new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 4L, java.time.LocalDateTime.of(2026, 7, 23, 10, 1));
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
        interaction.setInteractionId("interaction-" + taskId + "-" + type + "-" + status);
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
