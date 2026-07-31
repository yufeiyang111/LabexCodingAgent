package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.run.AgentRunLifecycleService;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentSsePublisherDurabilityTest {

    @Test
    void rejectsDurableSendBeforeTheRunIsBound() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentSsePublisher publisher = new AgentSsePublisher(emitter);

        assertThrows(IllegalStateException.class, () -> publisher.send("THINK", Map.of()));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void persistsAnEventBeforeWritingItToTheSseConnection() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(41L);
        when(lifecycle.appendEvent(eq(71L), eq("THINK"), any(), anyString())).thenReturn(event);

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L);
        publisher.send("THINK", Map.of("content", "Inspecting the project"));

        InOrder order = inOrder(lifecycle, emitter);
        order.verify(lifecycle).appendEvent(eq(71L), eq("THINK"), any(), anyString());
        order.verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void doesNotWriteAnEventWhenPersistenceFails() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.appendEvent(eq(71L), eq("THINK"), any(), anyString()))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L);

        assertThrows(IllegalStateException.class, () -> publisher.send("THINK", Map.of()));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void keepsTheDurableRunAliveWhenTheSseClientDisconnects() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(42L);
        when(lifecycle.appendEvent(eq(71L), eq("THINK"), any(), anyString())).thenReturn(event);
        org.mockito.Mockito.doThrow(new IOException("client disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L);

        assertDoesNotThrow(() -> publisher.send("THINK", Map.of("content", "Still running")));
        verify(lifecycle).appendEvent(eq(71L), eq("THINK"), any(), anyString());
    }

    @Test
    void forwardsTransientDeltasToTaskSubscribersAfterTheRunIsBound() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        java.util.concurrent.atomic.AtomicReference<Long> taskId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> type = new java.util.concurrent.atomic.AtomicReference<>();
        AgentSsePublisher publisher = new AgentSsePublisher(emitter, (id, eventType, payload) -> {
            taskId.set(id);
            type.set(eventType);
        });
        publisher.bindRun(mock(AgentRunLifecycleService.class), 71L);

        publisher.sendTransient("FINAL_DELTA", Map.of("delta", "live"));

        org.junit.jupiter.api.Assertions.assertEquals(71L, taskId.get());
        org.junit.jupiter.api.Assertions.assertEquals("FINAL_DELTA", type.get());
    }

    @Test
    void keepsForwardingTransientDeltasToReconnectedSubscribersAfterThePrimaryClientDisconnects() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(43L);
        when(lifecycle.appendEvent(eq(71L), eq("THINK"), any(), anyString())).thenReturn(event);
        org.mockito.Mockito.doThrow(new IOException("original browser disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        java.util.concurrent.atomic.AtomicReference<String> forwardedType = new java.util.concurrent.atomic.AtomicReference<>();
        AgentSsePublisher publisher = new AgentSsePublisher(emitter, (taskId, type, payload) -> forwardedType.set(type));
        publisher.bindRun(lifecycle, 71L);

        publisher.send("THINK", Map.of("content", "still running"));
        publisher.sendTransient("FINAL_DELTA", Map.of("delta", "visible after refresh"));

        org.junit.jupiter.api.Assertions.assertEquals("FINAL_DELTA", forwardedType.get());
    }

    @Test
    void sendsTransientDeltasWithoutPersistingEveryChunk() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L);

        publisher.sendTransient("THINK_DELTA", Map.of("delta", "live"));

        verify(lifecycle, never()).appendEvent(any(), anyString(), any(), anyString());
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }
}