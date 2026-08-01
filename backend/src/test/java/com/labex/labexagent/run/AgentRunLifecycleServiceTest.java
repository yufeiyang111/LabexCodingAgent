package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunOutbox;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentRunLifecycleServiceTest {

    @Test
    void initializesAQueuedRunWithItsFirstEventAndOutboxMessage() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.QUEUED);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(900L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunLifecycleService.TransitionResult result = service.initialize(
                task,
                Map.of("message", "Implement replay"),
                "task-71-queued");

        assertEquals("RUN_QUEUED", result.event().getEventType());
        assertEquals("queued", result.event().getState());
        assertEquals(1L, result.event().getSequenceNumber());
        assertEquals(1L, task.getLastEventSequence());
        assertEquals(1L, task.getRunVersion());
        verify(outboxMapper).insert(any(AgentRunOutbox.class));
    }

    @Test
    void appendsAStreamEventWithoutChangingTheRunStateAndAdvancesTheOptimisticVersion() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        task.setLastEventSequence(3L);
        task.setRunVersion(5L);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(903L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        service.setPartService(parts);
        AgentRunEvent event = service.appendEvent(
                71L,
                "THINK",
                Map.of("content", "Inspecting the project"),
                "task-71-think-4");

        assertEquals("running", event.getState());
        assertEquals("THINK", event.getEventType());
        assertEquals(4L, event.getSequenceNumber());
        assertEquals("running", task.getStatus());
        assertEquals(4L, task.getLastEventSequence());
        assertEquals(6L, task.getRunVersion());
        verify(outboxMapper).insert(any(AgentRunOutbox.class));
        verify(parts).recordEventPart(eq(71L), eq("THINK"), any(), eq(4L));
    }

    @Test
    void skipsAStaleExpectedStateEventAfterTheTaskHasReachedTerminalState() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.COMPLETED);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunEvent event = service.appendEventIfCurrent(
                71L,
                AgentRunState.WAITING_USER,
                "RUN_RECOVERY_WAITING",
                Map.of("reason", "stale startup snapshot"),
                "recovery-71-waiting");

        assertEquals(null, event);
        verify(taskMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void allocatesAfterThePersistedEventMaximumWhenTheTaskCursorIsStale() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RECOVERING);
        task.setLastEventSequence(25L);
        task.setRunVersion(25L);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(eventMapper.selectMaxSequenceByTaskId(71L)).thenReturn(26L);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(904L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunEvent event = service.appendEvent(
                71L,
                "THINK",
                Map.of("content", "Resume after restart"),
                "task-71-think-after-restart");

        assertEquals(27L, event.getSequenceNumber());
        assertEquals(27L, task.getLastEventSequence());
    }

    @Test
    void locksTheTaskBeforeReadingTheIdempotencyKey() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        List<String> calls = new ArrayList<>();
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenAnswer(invocation -> {
            calls.add("task-lock");
            return task;
        });
        when(eventMapper.selectOne(any())).thenAnswer(invocation -> {
            calls.add("idempotency-read");
            return null;
        });
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(906L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        service.appendEvent(71L, "THINK", Map.of("content", "Inspecting"), "task-71-lock-order");

        assertEquals(List.of("task-lock", "idempotency-read"), calls.subList(0, 2));
    }

    @Test
    void persistsStateEventAndOutboxMessageInOneTransition() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.QUEUED);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(901L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunLifecycleService.TransitionResult result = service.transition(
                71L,
                AgentRunState.PREPARING,
                "RUN_PREPARING",
                Map.of("reason", "worker accepted run"),
                "Preparing workspace",
                "Run accepted by worker",
                "run-71-preparing");

        assertTrue(result.stateChanged());
        assertEquals(1L, result.event().getSequenceNumber());
        assertEquals("preparing", result.event().getState());
        assertEquals("preparing", task.getStatus());
        assertEquals(1L, task.getLastEventSequence());
        assertEquals(1L, task.getRunVersion());

        ArgumentCaptor<AgentRunOutbox> outbox = ArgumentCaptor.forClass(AgentRunOutbox.class);
        verify(outboxMapper).insert(outbox.capture());
        assertEquals(901L, outbox.getValue().getEventId());
        assertEquals("pending", outbox.getValue().getStatus());
    }

    @Test
    void schedulesRetryStateAndRetryMetadataInTheSameCompareAndSet() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        java.time.LocalDateTime retryAt = java.time.LocalDateTime.of(2026, 7, 23, 10, 1);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(904L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        boolean scheduled = service.scheduleModelRetry(
                71L, 1, retryAt, Map.of("delayMs", 1_000), "Retrying model request",
                "Scheduled model retry 1", "model-retry-71-1");

        assertTrue(scheduled);
        assertEquals("retrying", task.getStatus());
        assertEquals(1, task.getRetryAttempts());
        assertEquals(retryAt, task.getNextRetryAt());
        assertEquals("Retrying model request", task.getCurrentStep());
        verify(outboxMapper).insert(any(AgentRunOutbox.class));
    }

    @Test
    void claimsADueRetryAsRecoveringUntilTheWorkerOwnsItsExecutionLease() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RETRYING);
        task.setRetryAttempts(1);
        task.setNextRetryAt(java.time.LocalDateTime.of(2026, 7, 23, 10, 0));
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(905L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        AgentRunLifecycleService.DispatchClaim claimed = service.claimScheduledRetry(
                71L, 1, java.time.LocalDateTime.of(2026, 7, 23, 10, 0, 1),
                "model-retry-start-71-1", "instance-a", 30_000L);

        assertTrue(claimed != null);
        assertEquals("instance-a", claimed.lease().owner());
        assertEquals("recovering", task.getStatus());
        assertEquals(null, task.getNextRetryAt());
    }

    @Test
    void cancelsRetryAndClearsItsScheduledDeadline() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RETRYING);
        task.setNextRetryAt(java.time.LocalDateTime.of(2026, 7, 24, 15, 0));
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(990L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        boolean cancelled = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper)
                .cancelScheduledRetry(71L, Map.of(), "retry-cancel-71");

        assertTrue(cancelled);
        assertEquals("cancelled", task.getStatus());
        assertEquals(null, task.getNextRetryAt());
        verify(outboxMapper).insert(any(AgentRunOutbox.class));
    }

    @Test
    void claimsAnExpiredLeaseAtomicallyBeforePersistingTakeoverEventAndOutbox() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        task.setRunVersion(8L);
        task.setLastEventSequence(3L);
        task.setExecutionEpoch(4L);
        task.setExecutionOwner("instance-old");
        task.setExecutionLeaseExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(991L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        AgentRunLifecycleService.RecoveryClaim claim = service.claimRecovery(
                71L, AgentRunState.RUNNING, "instance-new", 30_000L);

        assertEquals("instance-new", claim.owner());
        assertEquals(5L, claim.epoch());
        ArgumentCaptor<AgentRunEvent> event = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals("RUN_RECOVERY_TAKEOVER", event.getValue().getEventType());
        assertEquals("recovering", event.getValue().getState());
        assertEquals(4L, event.getValue().getSequenceNumber());
        JsonObject recoveryPayload = JsonParser.parseString(event.getValue().getPayload()).getAsJsonObject();
        assertTransitionAudit(recoveryPayload.getAsJsonObject("transition"),
                "running", "recovering", "RUN_RECOVERY_TAKEOVER", 5L);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AgentTask>> update = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(taskMapper).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("execution_epoch"));
        assertTrue(update.getValue().getSqlSet().contains("execution_owner"));
        assertTrue(update.getValue().getSqlSegment().contains("execution_lease_expires_at"));
        verify(outboxMapper).insert(any(AgentRunOutbox.class));
    }

    @Test
    void doesNotReturnASecondRecoveryClaimWhenTheTakeoverEventWasAlreadyPersisted() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        task.setExecutionEpoch(4L);
        AgentRunEvent existing = new AgentRunEvent();
        existing.setState(AgentRunState.RECOVERING.persistedStatus());
        existing.setEventType("RUN_RECOVERY_TAKEOVER");
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(existing);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        AgentRunLifecycleService.RecoveryClaim claim = service.claimRecovery(
                71L, AgentRunState.RUNNING, "instance-new", 30_000L);

        assertEquals(null, claim);
        verify(taskMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
    }

    @Test
    void refusesAnUnexpiredForeignLeaseWithoutWritingTakeoverEvent() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        task.setExecutionEpoch(4L);
        task.setExecutionOwner("instance-other");
        task.setExecutionLeaseExpiresAt(java.time.LocalDateTime.now().plusSeconds(30));
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(0);
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        AgentRunLifecycleService.RecoveryClaim claim = service.claimRecovery(
                71L, AgentRunState.RUNNING, "instance-new", 30_000L);

        assertEquals(null, claim);
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void reusesTheExistingEventForTheSameIdempotencyKey() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEvent existing = new AgentRunEvent();
        existing.setEventId(902L);
        existing.setState("preparing");
        existing.setEventType("RUN_PREPARING");
        existing.setSequenceNumber(1L);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task(AgentRunState.PREPARING));
        when(eventMapper.selectOne(any())).thenReturn(existing);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        AgentRunLifecycleService.TransitionResult result = service.transition(
                71L,
                AgentRunState.PREPARING,
                "RUN_PREPARING",
                Map.of(),
                "Preparing workspace",
                "Run accepted by worker",
                "run-71-preparing");

        assertFalse(result.stateChanged());
        assertEquals(existing, result.event());
        verify(taskMapper, never()).updateById(any(AgentTask.class));
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void treatsAHistoricSameTransitionAsAnIdempotentNoOpAfterTheTaskHasAdvanced() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEvent existing = new AgentRunEvent();
        existing.setEventId(906L);
        existing.setState(AgentRunState.RUNNING.persistedStatus());
        existing.setEventType("RUN_STATE_RUNNING");
        existing.setIdempotencyKey("run-71-running");
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task(AgentRunState.WAITING_APPROVAL));
        when(eventMapper.selectOne(any())).thenReturn(existing);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        AgentRunLifecycleService.TransitionResult result = service.transition(
                71L,
                AgentRunState.RUNNING,
                "RUN_STATE_RUNNING",
                Map.of(),
                "Thinking",
                null,
                "run-71-running");

        assertFalse(result.stateChanged());
        assertEquals(existing, result.event());
        verify(taskMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void rejectsReuseOfAnIdempotencyKeyForADifferentTransition() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEvent existing = new AgentRunEvent();
        existing.setEventId(908L);
        existing.setState(AgentRunState.RUNNING.persistedStatus());
        existing.setEventType("RUN_STATE_RUNNING");
        existing.setIdempotencyKey("run-71-collision");
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task(AgentRunState.WAITING_APPROVAL));
        when(eventMapper.selectOne(any())).thenReturn(existing);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        assertThrows(IllegalStateException.class, () -> service.transition(
                71L,
                AgentRunState.FAILED,
                "RUN_FAILED",
                Map.of(),
                "Failed",
                "Different operation",
                "run-71-collision"));
        verify(taskMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void doesNotReportHistoricIdempotencyAsAppliedWhenTheTaskIsStillInTheExpectedState() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.WAITING_USER);
        AgentRunEvent historic = new AgentRunEvent();
        historic.setEventId(907L);
        historic.setState(AgentRunState.RECOVERING.persistedStatus());
        historic.setEventType("RUN_INTERACTION_RESUME_QUEUED");
        historic.setIdempotencyKey("interaction-resume-duplicate");
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(historic);
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        boolean transitioned = service.transitionIfCurrent(
                71L,
                AgentRunState.WAITING_USER,
                AgentRunState.RECOVERING,
                "RUN_INTERACTION_RESUME_QUEUED",
                Map.of(),
                "Resuming after user response",
                "A persisted user response is ready",
                "interaction-resume-duplicate");

        assertFalse(transitioned);
        verify(taskMapper, never()).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
    }

    @Test
    void declinesAnExpectedStateTransitionWhenItsCompareAndSetLosesTheRace() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.WAITING_USER);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(0);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        boolean transitioned = service.transitionIfCurrent(
                71L,
                AgentRunState.WAITING_USER,
                AgentRunState.FAILED,
                "RUN_INTERACTION_TIMED_OUT",
                Map.of(),
                "Interaction timed out",
                "Timed out while waiting for user input",
                "interaction-timeout-wait-71");

        assertFalse(transitioned);
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void rejectsAConcurrentStreamEventAppendWithoutPublishingAnOutboxMessage() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(0);

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        assertThrows(IllegalStateException.class, () -> service.appendEvent(
                71L, "THINK", Map.of("content", "Inspecting"), "task-71-think-concurrent"));

        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void rejectsAConcurrentStateWriteWithoutPublishingAnOutboxMessage() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(0);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(905L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        assertThrows(IllegalStateException.class, () -> service.transition(
                71L, AgentRunState.FAILED, "RUN_FAILED", Map.of(), "Failure", "Concurrent write", "run-71-failed"));

        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    @Test
    void normalizesReasoningBlocksBeforePersistingAuthoritativeEventAndOutboxPayloads() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(908L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));

        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        service.appendEvent(
                71L,
                "FINAL",
                Map.of("content", "Visible <THINK data-kind='hidden'>private plan</THINKING> answer"),
                "task-71-final-normalized");

        ArgumentCaptor<AgentRunEvent> eventCaptor = ArgumentCaptor.forClass(AgentRunEvent.class);
        ArgumentCaptor<AgentRunOutbox> outboxCaptor = ArgumentCaptor.forClass(AgentRunOutbox.class);
        verify(eventMapper).insert(eventCaptor.capture());
        verify(outboxMapper).insert(outboxCaptor.capture());

        String eventPayload = eventCaptor.getValue().getPayload();
        String outboxPayload = outboxCaptor.getValue().getPayload();
        assertTrue(eventPayload.contains("Visible  answer"));
        assertTrue(outboxPayload.contains("Visible  answer"));
        assertFalse(eventPayload.toLowerCase().contains("think"));
        assertFalse(outboxPayload.toLowerCase().contains("think"));
        assertFalse(eventPayload.contains("private plan"));
        assertFalse(outboxPayload.contains("private plan"));
    }

    @Test
    void persistsCanonicalTransitionAuditAcrossEventOutboxAndPartProjection() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        task.setExecutionEpoch(4L);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(909L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);
        service.setPartService(parts);

        service.transition(
                71L,
                AgentRunState.WAITING_ENVIRONMENT,
                "RUN_ENVIRONMENT_BLOCKED",
                Map.of("reason", "dependency_resolution_failed", "detail", "Repository unavailable"),
                "Waiting for environment",
                "Dependency resolution failed",
                "run-71-environment-blocked");

        ArgumentCaptor<AgentRunEvent> eventCaptor = ArgumentCaptor.forClass(AgentRunEvent.class);
        ArgumentCaptor<AgentRunOutbox> outboxCaptor = ArgumentCaptor.forClass(AgentRunOutbox.class);
        ArgumentCaptor<Object> partPayloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventMapper).insert(eventCaptor.capture());
        verify(outboxMapper).insert(outboxCaptor.capture());
        verify(parts).recordEventPart(eq(71L), eq("RUN_ENVIRONMENT_BLOCKED"),
                partPayloadCaptor.capture(), eq(1L));

        JsonObject eventPayload = JsonParser.parseString(eventCaptor.getValue().getPayload()).getAsJsonObject();
        assertTransitionAudit(eventPayload.getAsJsonObject("transition"),
                "running", "waiting_environment", "dependency_resolution_failed", 4L);
        JsonObject outboxPayload = JsonParser.parseString(outboxCaptor.getValue().getPayload()).getAsJsonObject();
        assertTransitionAudit(outboxPayload.getAsJsonObject("payload").getAsJsonObject("transition"),
                "running", "waiting_environment", "dependency_resolution_failed", 4L);
        assertTrue(partPayloadCaptor.getValue() instanceof Map<?, ?>);
        Object transition = ((Map<?, ?>) partPayloadCaptor.getValue()).get("transition");
        assertTrue(transition instanceof Map<?, ?>);
        assertEquals("running", ((Map<?, ?>) transition).get("previousState"));
        assertEquals("waiting_environment", ((Map<?, ?>) transition).get("nextState"));
    }

    @Test
    void doesNotInventTransitionAuditForSameStateStreamEvents() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentTask task = task(AgentRunState.RUNNING);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(outboxMapper.insert(any(AgentRunOutbox.class))).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<AgentRunEvent>getArgument(0).setEventId(910L);
            return 1;
        }).when(eventMapper).insert(any(AgentRunEvent.class));
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        service.appendEvent(71L, "THINK", Map.of("content", "Inspecting"), "run-71-think-audit");

        ArgumentCaptor<AgentRunEvent> eventCaptor = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        JsonObject payload = JsonParser.parseString(eventCaptor.getValue().getPayload()).getAsJsonObject();
        assertFalse(payload.has("transition"));
    }

    @Test
    void rejectsAnIllegalTransitionBeforeWritingAnything() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        when(taskMapper.selectByTaskIdForUpdate(71L)).thenReturn(task(AgentRunState.QUEUED));
        when(eventMapper.selectOne(any())).thenReturn(null);
        AgentRunLifecycleService service = new AgentRunLifecycleService(taskMapper, eventMapper, outboxMapper);

        assertThrows(IllegalStateException.class, () -> service.transition(
                71L,
                AgentRunState.COMPLETED,
                "RUN_COMPLETED",
                Map.of(),
                "Completed",
                "Run completed",
                "run-71-completed"));

        verify(taskMapper, never()).updateById(any(AgentTask.class));
        verify(eventMapper, never()).insert(any(AgentRunEvent.class));
        verify(outboxMapper, never()).insert(any(AgentRunOutbox.class));
    }

    private void assertTransitionAudit(JsonObject transition, String previousState, String nextState,
                                       String reason, long executionEpoch) {
        assertEquals(previousState, transition.get("previousState").getAsString());
        assertEquals(nextState, transition.get("nextState").getAsString());
        assertEquals("agent_run_lifecycle", transition.get("actor").getAsString());
        assertEquals(reason, transition.get("reason").getAsString());
        assertEquals(executionEpoch, transition.get("executionEpoch").getAsLong());
        assertTrue(transition.get("stateChanged").getAsBoolean());
    }

    private AgentTask task(AgentRunState state) {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setStatus(state.persistedStatus());
        task.setLastEventSequence(0L);
        task.setRunVersion(0L);
        return task;
    }
}
