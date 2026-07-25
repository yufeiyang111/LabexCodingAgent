package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunOutbox;
import com.labex.mapper.AgentRunOutboxMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunOutboxPublisherTest {

    @Test
    void claimsAnAvailableMessageBeforePublishingAndMarksItPublished() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(outboxMapper, sink);

        int published = publisher.publishAvailable();

        assertEquals(1, published);
        assertEquals("published", outbox.getStatus());
        verify(sink).publish(outbox);
        verify(outboxMapper).updateById(outbox);
    }

    @Test
    void returnsAFailedDeliveryToPendingWithABackoff() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        AgentRunOutbox outbox = pendingOutbox();
        when(outboxMapper.selectList(any())).thenReturn(List.of(outbox));
        when(outboxMapper.update(any(), any())).thenReturn(1);
        when(outboxMapper.updateById(any(AgentRunOutbox.class))).thenReturn(1);
        doThrow(new IllegalStateException("queue unavailable")).when(sink).publish(outbox);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(outboxMapper, sink);
        LocalDateTime before = LocalDateTime.now();

        int published = publisher.publishAvailable();

        assertEquals(0, published);
        assertEquals("pending", outbox.getStatus());
        assertEquals(1, outbox.getAttempts());
        org.junit.jupiter.api.Assertions.assertTrue(outbox.getAvailableTime().isAfter(before));
    }

    @Test
    void doesNotDeliverARecordClaimedByAnotherPublisher() throws Exception {
        AgentRunOutboxMapper outboxMapper = mock(AgentRunOutboxMapper.class);
        AgentRunOutboxSink sink = mock(AgentRunOutboxSink.class);
        when(outboxMapper.selectList(any())).thenReturn(List.of(pendingOutbox()));
        when(outboxMapper.update(any(), any())).thenReturn(0);
        AgentRunOutboxPublisher publisher = new AgentRunOutboxPublisher(outboxMapper, sink);

        int published = publisher.publishAvailable();

        assertEquals(0, published);
        verify(sink, never()).publish(any(AgentRunOutbox.class));
    }

    private AgentRunOutbox pendingOutbox() {
        AgentRunOutbox outbox = new AgentRunOutbox();
        outbox.setOutboxId(81L);
        outbox.setEventId(901L);
        outbox.setTaskId(71L);
        outbox.setTopic("agent.run.event");
        outbox.setPayload("{}");
        outbox.setStatus("pending");
        outbox.setAttempts(0);
        outbox.setAvailableTime(LocalDateTime.now().minusMinutes(1));
        return outbox;
    }
}
