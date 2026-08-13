package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.commandsecurity.CommandApprovalOrchestrator;
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
        AgentRunInteraction answered = questionAnswer(71L);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task, task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), answered));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(answered);

        ArgumentCaptor<AgentStreamRequest> request = ArgumentCaptor.forClass(AgentStreamRequest.class);
        assertThat(scheduled).isTrue();
        verify(tasks).claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready"));
        verify(engine).resume(eq(7), eq(12), request.capture(), eq(71L), eq(true), eq(lease()), eq(answered));
        assertThat(request.getValue().getConversationId()).isEqualTo("conversation-1");
        assertThat(request.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(request.getValue().getResumeTaskId()).isEqualTo(71L);
        assertThat(request.getValue().getResumeInteractionId()).isNull();
        assertThat(request.getValue().getMessage()).isEqualTo("Continue the existing task from durable state.");
        assertThat(request.getValue().getResumeNote()).contains("answered");
    }

    @Test
    void retriesAResolvedInteractionAfterThePreviousWorkerReleasesItsLease() throws Exception {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask waiting = task(71L, "waiting_user");
        AgentRunInteraction answered = questionAnswer(71L);
        when(tasks.getOwnedTask(7, 12, 71L))
                .thenReturn(waiting, waiting, task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.deferred(),
                        AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), answered));
        CountDownLatch resumed = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            resumed.countDown();
            return null;
        }).when(engine).resume(
                eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), eq(answered));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean accepted = scheduler.resumeIfWaiting(answered);

        assertThat(accepted).isTrue();
        assertThat(resumed.await(2, TimeUnit.SECONDS)).isTrue();
        verify(tasks, org.mockito.Mockito.times(2)).claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready"));
        verify(engine).resume(
                eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), eq(answered));
    }

    @Test
    void reconcilesAPersistedResponseAfterProcessRestart() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        AgentRunInteraction answered = questionAnswer(71L);
        when(interactions.findResolvedAwaitingResume(100)).thenReturn(java.util.List.of(answered));
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"), task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), answered));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine, interactions);

        int resumed = scheduler.resumeResolvedInteractions();

        assertThat(resumed).isEqualTo(1);
        verify(engine).resume(
                eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), eq(answered));
    }

    @Test
    void doesNotEnqueueWhenThePersistedTaskDidNotEnterRecovery() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask waiting = task(71L, "waiting_user");
        AgentRunInteraction answered = questionAnswer(71L);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(waiting, task(71L, "waiting_user"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), answered));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(answered);

        assertThat(scheduled).isFalse();
        verify(engine, never()).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), any());
    }

    @Test
    void doesNotRetryWhenTheTransactionalClaimRejectsTheInteractionAsPermanentlyInvalid() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction answered = questionAnswer(71L);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.rejected());
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(answered);

        assertThat(scheduled).isFalse();
        verify(engine, never()).resume(anyInt(), anyInt(), any(AgentStreamRequest.class), anyLong(), anyBoolean(),
                any(), any());
    }

    @Test
    void resumesAQuestionThatWasSkippedSoTheAgentCanFinishSafely() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction cancelled = interaction(71L, "question", "cancelled");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"), task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-cancelled"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), cancelled));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(cancelled);

        assertThat(scheduled).isTrue();
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), eq(cancelled));
    }

    @Test
    void marksTheTaskFailedWhenAResolvedInteractionCannotBeQueued() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction answered = questionAnswer(71L);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_user"), task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-question-answered"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), answered));
        doThrow(new IllegalStateException("queue full")).when(engine)
                .resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                        eq(71L), eq(true), eq(lease()), eq(answered));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(answered);

        assertThat(scheduled).isFalse();
        verify(tasks).updateTask(71L, "failed", "Unable to resume after user response", "queue full",
                "interaction-resume-dispatch-interaction-71-question-answered");
    }

    @Test
    void delegatesApprovedOfflineNetworkRetryToServerExecutionWithoutModelReplay() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CommandApprovalOrchestrator commandApprovals = mock(CommandApprovalOrchestrator.class);
        AgentRunInteraction interaction = interaction(71L, "network", "approved");
        interaction.setRequestPayload("{\"requestKind\":\"offline_failure_retry\",\"request\":\"mvn test\"}");
        when(commandApprovals.canResumeOfflineNetworkRetry(interaction)).thenReturn(true);
        when(commandApprovals.resumeOfflineNetworkRetry(interaction)).thenReturn(true);
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine, null, commandApprovals);

        boolean scheduled = scheduler.resumeIfWaiting(interaction);

        assertThat(scheduled).isTrue();
        verify(commandApprovals, timeout(1_000L)).resumeOfflineNetworkRetry(interaction);
        verify(tasks, never()).claimResolvedInteractionDispatch(any(), any(), any(), any(), any(), any());
        verify(engine, never()).resume(anyInt(), anyInt(), any(AgentStreamRequest.class), anyLong(), anyBoolean(),
                any(), any());
    }

    @Test
    void queuesApprovedOfflineNetworkRetryWithoutBlockingTheApprovalRequestThread() throws Exception {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CommandApprovalOrchestrator commandApprovals = mock(CommandApprovalOrchestrator.class);
        AgentRunInteraction interaction = interaction(71L, "network", "approved");
        interaction.setRequestPayload("{\"requestKind\":\"offline_failure_retry\",\"request\":\"mvn test\"}");
        when(commandApprovals.canResumeOfflineNetworkRetry(interaction)).thenReturn(true);
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        when(commandApprovals.resumeOfflineNetworkRetry(interaction)).thenAnswer(invocation -> {
            executionStarted.countDown();
            releaseExecution.await(2, TimeUnit.SECONDS);
            return true;
        });
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine, null, commandApprovals);
        Thread delayedRelease = new Thread(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                releaseExecution.countDown();
            }
        }, "network-retry-test-release");
        delayedRelease.setDaemon(true);
        delayedRelease.start();

        long startedNanos = System.nanoTime();
        boolean scheduled = scheduler.resumeIfWaiting(interaction);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        try {
            assertThat(scheduled).isTrue();
            assertThat(elapsedMs).isLessThan(250L);
            assertThat(executionStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseExecution.countDown();
            delayedRelease.join(1_000L);
        }
    }

    @Test
    void resumesAnApprovedPermissionOnlyWhileItsTaskIsWaiting() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction approved = interaction(71L, "permission", "approved");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_approval"), task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-permission-approved"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), approved));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(approved);

        assertThat(scheduled).isTrue();
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), eq(approved));
    }

    @Test
    void fallsBackToGenericInteractionResumeForOfflineRetryWithoutACommandApproval() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CommandApprovalOrchestrator commandApprovals = mock(CommandApprovalOrchestrator.class);
        AgentRunInteraction interaction = interaction(71L, "network", "approved");
        interaction.setRequestPayload("{\"requestKind\":\"offline_failure_retry\",\"request\":\"mvn spring-boot:run\",\"toolCallId\":\"tool-1\"}");
        when(commandApprovals.canResumeOfflineNetworkRetry(interaction)).thenReturn(false);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_approval"), task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-network-approved"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), interaction));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine, null, commandApprovals);

        boolean scheduled = scheduler.resumeIfWaiting(interaction);

        assertThat(scheduled).isTrue();
        verify(commandApprovals, never()).resumeOfflineNetworkRetry(interaction);
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), eq(interaction));
    }

    @Test
    void onlyTreatsStructuredRequestKindAsOfflineRetry() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CommandApprovalOrchestrator commandApprovals = mock(CommandApprovalOrchestrator.class);
        AgentRunInteraction interaction = interaction(71L, "network", "approved");
        interaction.setRequestPayload("{\"requestKind\":\"explicit_command\",\"summary\":\"offline_failure_retry appears only in prose\"}");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_approval"), task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-network-approved"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), interaction));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine, null, commandApprovals);

        boolean scheduled = scheduler.resumeIfWaiting(interaction);

        assertThat(scheduled).isTrue();
        verify(commandApprovals, never()).resumeOfflineNetworkRetry(interaction);
        verify(engine).resume(eq(7), eq(12), org.mockito.ArgumentMatchers.any(AgentStreamRequest.class),
                eq(71L), eq(true), eq(lease()), eq(interaction));
    }

    @Test
    void resumesAnExpiredNetworkApprovalAsAGentleInteractionRecovery() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction timedOut = interaction(71L, "network", "timed_out");
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task(71L, "waiting_approval"), task(71L, "recovering"));
        when(tasks.claimResolvedInteractionDispatch(
                eq(7), eq(12), eq(71L), eq("interaction-71-network-timed_out"),
                eq("Resuming after user response"), eq("A persisted user response is ready")))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), timedOut));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean scheduled = scheduler.resumeIfWaiting(timedOut);

        assertThat(scheduled).isTrue();
        ArgumentCaptor<AgentStreamRequest> request = ArgumentCaptor.forClass(AgentStreamRequest.class);
        verify(engine).resume(eq(7), eq(12), request.capture(), eq(71L), eq(true), eq(lease()), eq(timedOut));
        assertThat(request.getValue().getResumeNote()).contains("timed_out");
        assertThat(request.getValue().getResumeNote()).contains("expired without a user decision");
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
