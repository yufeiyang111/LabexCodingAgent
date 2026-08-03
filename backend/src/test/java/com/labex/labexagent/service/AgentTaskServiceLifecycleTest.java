package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunState;
import com.labex.labexagent.run.BackgroundRunWorktreeService;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentTaskServiceLifecycleTest {

    @Test
    void createsAQueuedRunAndPersistsItsInitialLifecycleEvent() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        doAnswer(invocation -> {
            invocation.<AgentTask>getArgument(0).setTaskId(72L);
            return 1;
        }).when(taskMapper).insert(any(AgentTask.class));
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTaskService service = newTaskService(
                taskMapper,
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);
        StudentProject project = new StudentProject();
        project.setProjectId(12);

        AgentTask task = service.createTask(7, project, "conversation-1", "session-1", "build", "Implement replay");

        assertEquals("queued", task.getStatus());
        verify(lifecycle).initialize(eq(task), any(), eq("task-72-queued"));
    }


    @Test
    void usesDifferentOccurrenceKeysWhenTheSameStatePayloadHappensAgain() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTaskService service = newTaskService(
                mock(AgentTaskMapper.class),
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);

        service.updateTask(72L, "preparing", "Preparing workspace", "Worker accepted run");
        service.updateTask(72L, "preparing", "Preparing workspace", "Worker accepted run");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(lifecycle, times(2)).transition(
                eq(72L), eq(AgentRunState.PREPARING), eq("RUN_STATE_PREPARING"), any(),
                eq("Preparing workspace"), eq("Worker accepted run"), keys.capture());
        org.junit.jupiter.api.Assertions.assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void acceptsAResumeScopedIdempotencyKeyForTheFirstRunningTransition() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTaskService service = newTaskService(
                mock(AgentTaskMapper.class),
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);

        service.updateTask(72L, "running", "Thinking", "Resume", "resume-transition-72");

        verify(lifecycle).transition(
                eq(72L), eq(AgentRunState.RUNNING), eq("RUN_STATE_RUNNING"), any(),
                eq("Thinking"), eq("Resume"), eq("resume-transition-72"));
    }

    @Test
    void finalizesTimingInsideTheTerminalStateUpdateTransaction() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask task = new AgentTask();
        task.setTaskId(72L);
        task.setStatus("running");
        task.setSubmittedAt(java.time.LocalDateTime.of(2026, 7, 23, 10, 0));
        task.setActiveSegmentStartedAt(java.time.LocalDateTime.of(2026, 7, 23, 10, 0, 5));
        task.setActiveElapsedMs(0L);
        when(taskMapper.selectById(72L)).thenReturn(task);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        doAnswer(invocation -> {
            task.setStatus("completed");
            return null;
        }).when(lifecycle).transition(eq(72L), eq(AgentRunState.COMPLETED), any(), any(), any(), any(), any());
        AgentTaskService service = newTaskService(
                taskMapper,
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);

        service.updateTask(72L, "completed", "Done", "Run completed");

        org.junit.jupiter.api.Assertions.assertNull(task.getStartedAt());
        org.junit.jupiter.api.Assertions.assertNotNull(task.getFinishedAt());
        org.junit.jupiter.api.Assertions.assertNull(task.getActiveSegmentStartedAt());
        org.junit.jupiter.api.Assertions.assertTrue(task.getElapsedMs() >= 0L);
    }

    @Test
    void timingUpdatesDoNotWriteTheWholeTaskOrOverwriteRunVersion() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask task = new AgentTask();
        task.setTaskId(72L);
        task.setStatus("queued");
        task.setRunVersion(9L);
        task.setActiveElapsedMs(120L);
        when(taskMapper.selectById(72L)).thenReturn(task);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentTaskService service = newTaskService(
                taskMapper,
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                mock(AgentRunLifecycleService.class));

        service.startTiming(72L, java.time.LocalDateTime.of(2026, 7, 23, 10, 1));

        assertEquals(9L, task.getRunVersion());
        org.junit.jupiter.api.Assertions.assertNotNull(task.getActiveSegmentStartedAt());
        verify(taskMapper, never()).updateById(any(AgentTask.class));
    }

    @Test
    void schedulesAModelRetryWithPausedActiveTimingAndDurableMetadata() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask task = new AgentTask();
        task.setTaskId(72L);
        task.setStatus("running");
        task.setRetryAttempts(0);
        task.setActiveSegmentStartedAt(java.time.LocalDateTime.of(2026, 7, 23, 10, 0));
        task.setActiveElapsedMs(100L);
        when(taskMapper.selectById(72L)).thenReturn(task);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent retryEvent = new AgentRunEvent();
        retryEvent.setEventType("RUN_MODEL_RETRY_SCHEDULED");
        retryEvent.setSequenceNumber(9L);
        when(lifecycle.scheduleModelRetryResult(eq(72L), eq(1), any(), any(), any(), any(), any()))
                .thenReturn(new AgentRunLifecycleService.TransitionResult(retryEvent, true));
        AgentTaskService service = newTaskService(
                taskMapper,
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);

        AgentTaskService.ModelRetrySchedule schedule = service.scheduleModelRetry(
                72L, 2, 1_000L, "temporary upstream failure");

        org.junit.jupiter.api.Assertions.assertNotNull(schedule);
        assertEquals(1, schedule.attempt());
        assertSame(retryEvent, schedule.event());
        org.junit.jupiter.api.Assertions.assertNull(task.getActiveSegmentStartedAt());
        org.junit.jupiter.api.Assertions.assertTrue(task.getActiveElapsedMs() >= 100L);
        verify(lifecycle).scheduleModelRetryResult(eq(72L), eq(1), any(), any(), any(), any(), any());
    }

    @Test
    void finalizesAnActiveCancellationThroughCancellingBeforeCancelled() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask running = new AgentTask();
        running.setTaskId(72L);
        running.setStatus("running");
        AgentTask cancelling = new AgentTask();
        cancelling.setTaskId(72L);
        cancelling.setStatus("cancelling");
        when(taskMapper.selectById(72L)).thenReturn(running, running, cancelling);

        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.transitionIfCurrent(eq(72L), eq(AgentRunState.RUNNING), eq(AgentRunState.CANCELLING),
                any(), any(), any(), any(), any())).thenReturn(true);
        AgentRunEvent cancelledEvent = new AgentRunEvent();
        cancelledEvent.setEventType("RUN_CANCELLED");
        cancelledEvent.setSequenceNumber(12L);
        when(lifecycle.transitionIfCurrentResult(eq(72L), eq(AgentRunState.CANCELLING), eq(AgentRunState.CANCELLED),
                any(), any(), any(), any(), any()))
                .thenReturn(new AgentRunLifecycleService.TransitionResult(cancelledEvent, true));
        AgentTaskService service = newTaskService(
                taskMapper,
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);

        AgentTaskService.CancellationFinalization finalization = service.finalizeCancellationWithEvent(
                72L, "Cancelled", "User cancelled");

        org.junit.jupiter.api.Assertions.assertTrue(finalization.finalized());
        assertSame(cancelledEvent, finalization.event());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(lifecycle);
        order.verify(lifecycle).transitionIfCurrent(eq(72L), eq(AgentRunState.RUNNING), eq(AgentRunState.CANCELLING),
                any(), any(), any(), any(), any());
        order.verify(lifecycle).transitionIfCurrentResult(eq(72L), eq(AgentRunState.CANCELLING), eq(AgentRunState.CANCELLED),
                any(), any(), any(), any(), any());
    }

    @Test
    void persistsEnvironmentBlockerAndOnlyResumesItThroughQueued() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask running = new AgentTask(); running.setTaskId(72L); running.setStatus("running");
        AgentTask waiting = new AgentTask(); waiting.setTaskId(72L); waiting.setStatus("waiting_environment");
        waiting.setLastEventSequence(4L);
        when(taskMapper.selectById(72L)).thenReturn(running, waiting);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.transitionIfCurrent(eq(72L), eq(AgentRunState.RUNNING), eq(AgentRunState.WAITING_ENVIRONMENT),
                any(), any(), any(), any(), any())).thenReturn(true);
        AgentRunExecutionLeaseService executionLeases = mock(AgentRunExecutionLeaseService.class);
        when(executionLeases.instanceId()).thenReturn("instance-a");
        when(executionLeases.leaseDurationMs()).thenReturn(30_000L);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                72L, "instance-a", 1L, java.time.LocalDateTime.now().plusSeconds(30));
        when(lifecycle.claimDispatch(eq(72L), eq(AgentRunState.WAITING_ENVIRONMENT), eq(AgentRunState.QUEUED),
                eq("RUN_ENVIRONMENT_RESUME"), any(), any(), any(), any(), eq("instance-a"), any(Long.class)))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease));
        AgentTaskService service = newTaskService(taskMapper, mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class), lifecycle, executionLeases);

        org.junit.jupiter.api.Assertions.assertTrue(service.waitForEnvironment(72L, "Waiting", "DNS failed", "DNS_UNAVAILABLE"));
        org.junit.jupiter.api.Assertions.assertNotNull(service.claimEnvironmentResume(72L));

        verify(lifecycle).transitionIfCurrent(eq(72L), eq(AgentRunState.RUNNING), eq(AgentRunState.WAITING_ENVIRONMENT),
                eq("RUN_ENVIRONMENT_BLOCKED"), any(), eq("Waiting"), eq("DNS failed"), any());
        verify(lifecycle).claimDispatch(eq(72L), eq(AgentRunState.WAITING_ENVIRONMENT), eq(AgentRunState.QUEUED),
                eq("RUN_ENVIRONMENT_RESUME"), any(), eq("Queued for resume"), any(), any(), eq("instance-a"), any(Long.class));
    }

    @Test
    void includesTheDurableInteractionIdentityInTheResumeEvent() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask waiting = new AgentTask();
        waiting.setTaskId(72L);
        waiting.setStatus("waiting_user");
        when(taskMapper.selectById(72L)).thenReturn(waiting);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService executionLeases = mock(AgentRunExecutionLeaseService.class);
        when(executionLeases.instanceId()).thenReturn("instance-a");
        when(executionLeases.leaseDurationMs()).thenReturn(30_000L);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                72L, "instance-a", 1L, java.time.LocalDateTime.now().plusSeconds(30));
        when(lifecycle.claimDispatch(eq(72L), eq(AgentRunState.WAITING_USER), eq(AgentRunState.RECOVERING),
                eq("RUN_INTERACTION_RESUME_QUEUED"), any(), eq("Resuming"), eq("User response persisted"), any(),
                eq("instance-a"), any(Long.class)))
                .thenReturn(new AgentRunLifecycleService.DispatchClaim(lease));
        AgentTaskService service = newTaskService(taskMapper, mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class), lifecycle, executionLeases);

        org.junit.jupiter.api.Assertions.assertNotNull(service.claimInteractionResume(
                72L, "interaction-72", "Resuming", "User response persisted"));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<java.util.Map> payload = ArgumentCaptor.forClass(java.util.Map.class);
        verify(lifecycle).claimDispatch(eq(72L), eq(AgentRunState.WAITING_USER), eq(AgentRunState.RECOVERING),
                eq("RUN_INTERACTION_RESUME_QUEUED"), payload.capture(), eq("Resuming"),
                eq("User response persisted"), eq(com.labex.labexagent.run.AgentRunTransitionKey.forInteractionResume(
                        72L, "interaction-72")), eq("instance-a"), any(Long.class));
        assertEquals("interaction-72", payload.getValue().get("interactionId"));
    }

    @Test
    void returnsTheAuthoritativeLifecycleEventForLiveProjection() throws Exception {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent authoritativeEvent = new AgentRunEvent();
        authoritativeEvent.setEventId(901L);
        authoritativeEvent.setSequenceNumber(19L);
        authoritativeEvent.setEventType("RUN_STATE_PREPARING");
        when(lifecycle.transition(eq(72L), eq(AgentRunState.PREPARING),
                eq("RUN_STATE_PREPARING"), any(), eq("Preparing workspace"),
                eq("Worker accepted run"), eq("transition-72")))
                .thenReturn(new AgentRunLifecycleService.TransitionResult(authoritativeEvent, true));
        AgentTaskService service = newTaskService(
                mock(AgentTaskMapper.class),
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);

        Object returned = AgentTaskService.class.getMethod("updateTask", Long.class, String.class,
                        String.class, String.class, String.class)
                .invoke(service, 72L, "preparing", "Preparing workspace", "Worker accepted run",
                        "transition-72");

        assertSame(authoritativeEvent, returned);
    }

    @Test
    void routesKnownRunStatesThroughTheLifecycleService() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentTaskService service = newTaskService(
                mock(AgentTaskMapper.class),
                mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class),
                lifecycle);

        service.updateTask(72L, "preparing", "Preparing workspace", "Worker accepted run");

        verify(lifecycle).transition(
                eq(72L),
                eq(AgentRunState.PREPARING),
                eq("RUN_STATE_PREPARING"),
                any(),
                eq("Preparing workspace"),
                eq("Worker accepted run"),
                any());
    }

    private AgentTaskService newTaskService(AgentTaskMapper taskMapper,
                                            AgentChangeSetMapper changeSetMapper,
                                            AgentFileChangeMapper fileChangeMapper,
                                            AgentRunLifecycleService lifecycle) {
        return newTaskService(taskMapper, changeSetMapper, fileChangeMapper, lifecycle,
                mock(AgentRunExecutionLeaseService.class));
    }

    private AgentTaskService newTaskService(AgentTaskMapper taskMapper,
                                            AgentChangeSetMapper changeSetMapper,
                                            AgentFileChangeMapper fileChangeMapper,
                                            AgentRunLifecycleService lifecycle,
                                            AgentRunExecutionLeaseService executionLeases) {
        return new AgentTaskService(taskMapper, changeSetMapper, fileChangeMapper, lifecycle,
                executionLeases, mock(BackgroundRunWorktreeService.class));
    }
}
