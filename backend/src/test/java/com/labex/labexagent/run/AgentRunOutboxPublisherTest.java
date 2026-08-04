package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunOutbox;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentRunOutboxPublisherTest {

    @Test
    void projectsTheAuthoritativeEventBeforePublishingAndMarksItPublished() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(0, 1, 0, 1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        when(eventMapper.selectById(901L)).thenReturn(authoritativeEvent());
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);

        int published = publisher.publishAvailable();

        assertEquals(1, published);
        assertEquals("published", outbox.getStatus());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(partService).recordEventPart(eq(71L), eq("RUN_STATE_COMPLETED"), payload.capture(), eq(9L));
        assertEquals("authoritative", ((Map<?, ?>) payload.getValue()).get("source"));
        InOrder order = inOrder(partService, sink);
        order.verify(partService).recordEventPart(eq(71L), eq("RUN_STATE_COMPLETED"), any(), eq(9L));
        order.verify(sink).publish(outbox);
        verify(outboxMapper).updateById(outbox);
    }

    @Test
    void projectionFailureReturnsDeliveryToPendingWithoutPublishing() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(0, 1, 0, 1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        when(eventMapper.selectById(901L)).thenReturn(authoritativeEvent());
        doThrow(new IllegalStateException("transcript unavailable"))
                .when(partService).recordEventPart(71L, "RUN_STATE_COMPLETED", Map.of("source", "authoritative"), 9L);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);
        LocalDateTime before = LocalDateTime.now();

        int published = publisher.publishAvailable();

        assertEquals(0, published);
        assertEquals("pending", outbox.getStatus());
        assertEquals(1, outbox.getAttempts());
        assertTrue(outbox.getAvailableTime().isAfter(before));
        verify(sink, never()).publish(any(AgentRunOutbox.class));
    }

    @Test
    void retriesTheSameProjectionBeforePublishingAfterATransientFailure() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(0, 1, 0, 1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        when(eventMapper.selectById(901L)).thenReturn(authoritativeEvent());
        when(partService.recordEventPart(
                71L, "RUN_STATE_COMPLETED", Map.of("source", "authoritative"), 9L))
                .thenThrow(new IllegalStateException("transcript unavailable"))
                .thenReturn(null);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);

        assertEquals(0, publisher.publishAvailable());
        outbox.setAvailableTime(LocalDateTime.now().minusSeconds(1));
        assertEquals(1, publisher.publishAvailable());

        assertEquals("published", outbox.getStatus());
        assertEquals(2, outbox.getAttempts());
        verify(partService, times(2))
                .recordEventPart(71L, "RUN_STATE_COMPLETED", Map.of("source", "authoritative"), 9L);
        verify(sink, times(1)).publish(outbox);
    }

    @Test
    void missingAuthoritativeEventIsRetriedAndNeverPublished() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(0, 1, 0, 1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        when(eventMapper.selectById(901L)).thenReturn(null);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);

        assertEquals(0, publisher.publishAvailable());

        assertEquals("pending", outbox.getStatus());
        verify(partService, never()).recordEventPart(any(), any(), any(), anyLong());
        verify(sink, never()).publish(any(AgentRunOutbox.class));
    }

    @Test
    void rejectsTaskIdentityMismatchBeforeProjectionOrPublishing() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox outbox = pendingOutbox();
        AgentRunEvent event = authoritativeEvent();
        event.setTaskId(72L);
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(0, 1, 0, 1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        when(eventMapper.selectById(901L)).thenReturn(event);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);

        assertEquals(0, publisher.publishAvailable());

        assertEquals("pending", outbox.getStatus());
        verify(partService, never()).recordEventPart(any(), any(), any(), anyLong());
        verify(sink, never()).publish(any(AgentRunOutbox.class));
    }

    @Test
    void defersLaterSequenceWhileAnEarlierOutboxIsUnpublished() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox laterOutbox = pendingOutbox();
        laterOutbox.setOutboxId(82L);
        laterOutbox.setEventId(902L);
        AgentRunEvent laterEvent = authoritativeEvent();
        laterEvent.setEventId(902L);
        laterEvent.setSequenceNumber(10L);
        when(outboxMapper.selectList(any())).thenReturn(List.of(laterOutbox));
        when(outboxMapper.countUnpublishedBeforeSequence(71L, 10L)).thenReturn(1L);
        when(outboxMapper.update(any(), any())).thenReturn(0, 1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        when(eventMapper.selectById(902L)).thenReturn(laterEvent);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);

        assertEquals(0, publisher.publishAvailable());

        assertEquals("pending", laterOutbox.getStatus());
        assertEquals(0, laterOutbox.getAttempts());
        verify(partService, never()).recordEventPart(any(), any(), any(), anyLong());
        verify(sink, never()).publish(any(AgentRunOutbox.class));
    }

    @Test
    void recoversExpiredPublishingClaimsBeforeReadingPendingMessages() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        when(outboxMapper.update(any(), any())).thenReturn(2);
        when(outboxMapper.selectList(any())).thenReturn(List.of());
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);

        assertEquals(0, publisher.publishAvailable());

        verify(outboxMapper).update(isNull(), any());
        verify(sink, never()).publish(any(AgentRunOutbox.class));
    }

    @Test
    void doesNotDeliverARecordClaimedByAnotherPublisher() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunEventMapper eventMapper = mock(AgentRunEventMapper.class);
        AgentRunPartService partService = mock(AgentRunPartService.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        when(outboxMapper.selectList(any())).thenReturn(List.of(pendingOutbox()));
        when(outboxMapper.update(any(), any())).thenReturn(0, 0);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(
                outboxMapper, eventMapper, partService, sink);

        int published = publisher.publishAvailable();

        assertEquals(0, published);
        verify(eventMapper, never()).selectById(any());
        verify(partService, never()).recordEventPart(any(), any(), any(), anyLong());
        verify(sink, never()).publish(any(AgentRunOutbox.class));
    }

    private AgentRunEvent authoritativeEvent() {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(901L);
        event.setTaskId(71L);
        event.setSequenceNumber(9L);
        event.setEventType("RUN_STATE_COMPLETED");
        event.setPayload("{\"source\":\"authoritative\"}");
        return event;
    }

    private AgentRunOutbox pendingOutbox() {
        AgentRunOutbox outbox = new AgentRunOutbox();
        outbox.setOutboxId(81L);
        outbox.setEventId(901L);
        outbox.setTaskId(71L);
        outbox.setTopic("agent.run.event");
        outbox.setPayload("{\"payload\":{\"source\":\"stale-outbox-envelope\"}}");
        outbox.setStatus("pending");
        outbox.setAttempts(0);
        outbox.setAvailableTime(LocalDateTime.now().minusMinutes(1));
        return outbox;
    }
}