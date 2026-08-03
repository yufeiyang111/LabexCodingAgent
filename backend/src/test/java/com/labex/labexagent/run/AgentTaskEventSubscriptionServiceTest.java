package com.labex.labexagent.run;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentTaskEventSubscriptionServiceTest {

    @Test
    void logsTheReplayBoundaryWithoutLoggingEventPayloadContent() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(71L);
        event.setSequenceNumber(8L);
        event.setEventType("THINK");
        event.setPayload("{\"content\":\"secret model reasoning\"}");
        when(replay.eventsAfter(7, 12, 71L, 7L)).thenReturn(List.of(event));
        SseEmitter emitter = mock(SseEmitter.class);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(AgentTaskEventSubscriptionService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new AgentTaskEventSubscriptionService(replay).subscribe(7, 12, 71L, 7L, emitter);
        } finally {
            logger.detachAppender(appender);
        }

        String logs = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
        org.junit.jupiter.api.Assertions.assertTrue(logs.contains("TASK_EVENT_REPLAY_COMPLETE taskId=71"));
        org.junit.jupiter.api.Assertions.assertFalse(logs.contains("secret model reasoning"));
    }

    @Test
    void replaysDurableEventsBeforeKeepingTheTaskSubscriptionOpen() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(71L);
        event.setSequenceNumber(8L);
        event.setEventType("THINK");
        event.setPayload("{\"content\":\"Recovered\"}");
        when(replay.eventsAfter(7, 12, 71L, 7L)).thenReturn(List.of(event));
        SseEmitter emitter = mock(SseEmitter.class);
        AgentTaskEventSubscriptionService subscriptions = new AgentTaskEventSubscriptionService(replay);

        subscriptions.subscribe(7, 12, 71L, 7L, emitter);

        verify(replay).eventsAfter(7, 12, 71L, 7L);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test
    void deliversACommittedOutboxEventOnlyToMatchingTaskSubscribers() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        when(replay.eventsAfter(7, 12, 71L, 0L)).thenReturn(List.of());
        SseEmitter matching = mock(SseEmitter.class);
        SseEmitter unrelated = mock(SseEmitter.class);
        AgentTaskEventSubscriptionService subscriptions = new AgentTaskEventSubscriptionService(replay);
        subscriptions.subscribe(7, 12, 71L, 0L, matching);
        subscriptions.subscribe(7, 12, 72L, 0L, unrelated);

        subscriptions.onApplicationEvent(new AgentRunOutboxMessage(91L, 801L, 71L, "agent.run.event", """
                {"taskId":71,"sequence":1,"eventType":"THINK","payload":{"content":"live"}}
                """));

        verify(matching).send(any(SseEmitter.SseEventBuilder.class));
        verify(unrelated, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void pollsDurableEventsEvenWhenAnotherInstanceConsumedTheOutboxNotification() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        AgentRunEvent started = event(71L, 1L, "COMPACTION_STARTED", "{\"strategy\":\"model\"}");
        AgentRunEvent completed = event(71L, 2L, "COMPACTION_COMPLETED", "{\"strategy\":\"model\"}");
        when(replay.eventsAfter(7, 12, 71L, 0L))
                .thenReturn(List.of())
                .thenReturn(List.of(started, completed));
        when(replay.eventsAfter(7, 12, 71L, 2L)).thenReturn(List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        AgentTaskEventSubscriptionService subscriptions = new AgentTaskEventSubscriptionService(replay);
        subscriptions.subscribe(7, 12, 71L, 0L, emitter);

        subscriptions.pollDurableEvents();
        subscriptions.pollDurableEvents();

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(replay).eventsAfter(7, 12, 71L, 2L);
    }

    @Test
    void drainsDurableEventsAppendedAfterTheTaskFirstBecomesTerminal() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        AgentRunEvent failed = event(71L, 1L, "RUN_STATE_FAILED", "{}");
        failed.setState("failed");
        AgentRunEvent finalEvent = event(71L, 2L, "FINAL", "{\"content\":\"visible failure\"}");
        finalEvent.setState("failed");
        AgentRunEvent done = event(71L, 3L, "DONE", "{}");
        done.setState("failed");
        when(replay.eventsAfter(7, 12, 71L, 0L))
                .thenReturn(List.of())
                .thenReturn(List.of(failed));
        when(replay.eventsAfter(7, 12, 71L, 1L)).thenReturn(List.of(finalEvent, done));
        when(replay.eventsAfter(7, 12, 71L, 3L)).thenReturn(List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        AgentTaskEventSubscriptionService subscriptions = new AgentTaskEventSubscriptionService(replay, 0L);
        subscriptions.subscribe(7, 12, 71L, 0L, emitter);

        subscriptions.pollDurableEvents();
        verify(emitter, never()).complete();
        subscriptions.pollDurableEvents();
        verify(emitter, never()).complete();
        subscriptions.pollDurableEvents();

        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void catchesUpMissingSequencesBeforeDeliveringANewerOutboxNotification() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        AgentRunEvent first = event(71L, 1L, "COMPACTION_STARTED", "{}");
        AgentRunEvent second = event(71L, 2L, "COMPACTION_COMPLETED", "{}");
        AgentRunEvent third = event(71L, 3L, "RUN_STATE_COMPLETED", "{}");
        when(replay.eventsAfter(7, 12, 71L, 0L))
                .thenReturn(List.of())
                .thenReturn(List.of(first, second, third));
        when(replay.eventsAfter(7, 12, 71L, 3L)).thenReturn(List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        AgentTaskEventSubscriptionService subscriptions = new AgentTaskEventSubscriptionService(replay, 0L);
        subscriptions.subscribe(7, 12, 71L, 0L, emitter);

        subscriptions.onApplicationEvent(new AgentRunOutboxMessage(91L, 803L, 71L, "agent.run.event", """
                {"taskId":71,"sequence":3,"state":"completed","eventType":"RUN_STATE_COMPLETED","payload":{}}
                """));
        verify(emitter, never()).complete();
        subscriptions.pollDurableEvents();

        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void forwardsLiveTransientDeltasToMatchingSubscribersWithoutAReplaySequence() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        when(replay.eventsAfter(7, 12, 71L, 0L)).thenReturn(List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        AgentTaskEventSubscriptionService subscriptions = new AgentTaskEventSubscriptionService(replay);
        subscriptions.subscribe(7, 12, 71L, 0L, emitter);

        subscriptions.publishTransient(71L, "FINAL_DELTA", java.util.Map.of("delta", "live"));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    private AgentRunEvent event(Long taskId, Long sequence, String eventType, String payload) {
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(taskId);
        event.setSequenceNumber(sequence);
        event.setEventType(eventType);
        event.setState("RUN_STATE_COMPLETED".equals(eventType) ? "completed" : "running");
        event.setPayload(payload);
        return event;
    }

    @Test
    void clientDisconnectOnlyRemovesTheSubscriptionAndDoesNotRequireCancellationServices() throws Exception {
        AgentRunEventReplayService replay = mock(AgentRunEventReplayService.class);
        when(replay.eventsAfter(7, 12, 71L, 0L)).thenReturn(List.of());
        SseEmitter emitter = mock(SseEmitter.class);
        final Runnable[] onCompletion = new Runnable[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            onCompletion[0] = invocation.getArgument(0);
            return null;
        }).when(emitter).onCompletion(any(Runnable.class));
        AgentTaskEventSubscriptionService subscriptions = new AgentTaskEventSubscriptionService(replay);
        subscriptions.subscribe(7, 12, 71L, 0L, emitter);

        onCompletion[0].run();
        subscriptions.onApplicationEvent(new AgentRunOutboxMessage(92L, 802L, 71L, "agent.run.event", """
                {"taskId":71,"sequence":1,"eventType":"THINK","payload":{"content":"ignored"}}
                """));

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
