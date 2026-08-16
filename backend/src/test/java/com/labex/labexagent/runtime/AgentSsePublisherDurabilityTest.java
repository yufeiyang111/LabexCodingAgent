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
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.AgentRunLifecycleService;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AgentSsePublisherDurabilityTest {

    @Test
    void exposesWhetherDurableRunBindingExists() {
        AgentSsePublisher publisher = new AgentSsePublisher(mock(SseEmitter.class));

        org.junit.jupiter.api.Assertions.assertFalse(publisher.isBound());
        publisher.bindRun(mock(AgentRunLifecycleService.class), 71L);
        org.junit.jupiter.api.Assertions.assertTrue(publisher.isBound());
    }

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
    void persistsWithTheBoundExecutionFence() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        ExecutionFence fence = new ExecutionFence(71L, "worker-a", 4L);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(41L);
        when(lifecycle.appendEvent(eq(fence), eq(71L), eq("THINK"), any(), anyString())).thenReturn(event);

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L, fence);
        publisher.send("THINK", Map.of("content", "Inspecting the project"));

        verify(lifecycle).appendEvent(eq(fence), eq(71L), eq("THINK"), any(), anyString());
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
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
    void normalizesDurablePayloadBeforePersistenceAndLiveDelivery() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(42L);
        when(lifecycle.appendEvent(eq(71L), eq("FINAL"), any(), anyString())).thenReturn(event);

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L);
        publisher.send("FINAL", Map.of(
                "content", "Visible <THINK data-kind='hidden'>private plan</THINKING> answer"));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(lifecycle).appendEvent(eq(71L), eq("FINAL"), payloadCaptor.capture(), anyString());
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedPayload = (Map<String, Object>) payloadCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("Visible  answer", persistedPayload.get("content"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> frameCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(frameCaptor.capture());
        String frame = frameData(frameCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(frame.contains("Visible  answer"));
        org.junit.jupiter.api.Assertions.assertFalse(frame.toLowerCase().contains("think"));
        org.junit.jupiter.api.Assertions.assertFalse(frame.contains("private plan"));
    }

    @Test
    void normalizesTransientAndLegacyReplayFramesAtTheFinalSseBoundary() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        java.util.concurrent.atomic.AtomicReference<Object> forwarded = new java.util.concurrent.atomic.AtomicReference<>();
        AgentSsePublisher publisher = new AgentSsePublisher(emitter, (taskId, type, payload) -> forwarded.set(payload));
        publisher.bindRun(mock(AgentRunLifecycleService.class), 71L);

        publisher.sendTransient("FINAL_DELTA", Map.of(
                "delta", "Visible &lt;THINK&gt;private&lt;/THINKING&gt; answer"));
        publisher.sendPersisted(9L, "THINK", Map.of(
                "content", "&lt;THINK&gt;reasoning text&lt;/THINKING&gt;"));

        @SuppressWarnings("unchecked")
        Map<String, Object> forwardedPayload = (Map<String, Object>) forwarded.get();
        org.junit.jupiter.api.Assertions.assertEquals("Visible  answer", forwardedPayload.get("delta"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.times(2)).send(frames.capture());
        String liveFrame = frameData(frames.getAllValues().get(0));
        String replayFrame = frameData(frames.getAllValues().get(1));
        org.junit.jupiter.api.Assertions.assertTrue(liveFrame.contains("Visible  answer"));
        org.junit.jupiter.api.Assertions.assertFalse(liveFrame.toLowerCase().contains("think"));
        org.junit.jupiter.api.Assertions.assertFalse(liveFrame.contains("private"));
        org.junit.jupiter.api.Assertions.assertTrue(replayFrame.contains("reasoning text"));
        org.junit.jupiter.api.Assertions.assertFalse(replayFrame.toLowerCase().contains("<think"));
        org.junit.jupiter.api.Assertions.assertFalse(replayFrame.toLowerCase().contains("&lt;think"));
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
    void persistsTheDurableFinalAfterThePrimaryTransientFrameDisconnects() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent finalEvent = new AgentRunEvent();
        finalEvent.setSequenceNumber(43L);
        when(lifecycle.appendEvent(eq(71L), eq("FINAL"), any(), anyString())).thenReturn(finalEvent);
        org.mockito.Mockito.doThrow(new IOException("client disconnected"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L);

        assertDoesNotThrow(() -> {
            publisher.sendTransient("FINAL_DELTA", Map.of("delta", "Visible final reply"));
            publisher.send("FINAL", Map.of("content", "Visible final reply"));
        });

        verify(lifecycle).appendEvent(eq(71L), eq("FINAL"), any(), anyString());
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

    @Test
    void fillsPersistedSequenceGapsBeforeProjectingANewerDirectEvent() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunEvent plan = event(41L, "PLAN_UPDATE", "{\"planRevision\":1}");
        AgentRunEvent think = event(42L, "THINK", "{\"content\":\"after plan\"}");
        when(lifecycle.currentEventSequence(71L)).thenReturn(40L);
        when(lifecycle.appendEvent(eq(71L), eq("THINK"), any(), anyString())).thenReturn(think);
        when(lifecycle.eventsAfter(eq(71L), eq(40L), eq(42L))).thenReturn(java.util.List.of(plan, think));

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L);
        publisher.send("THINK", Map.of("content", "after plan"));
        publisher.sendPersisted(41L, "PLAN_UPDATE", Map.of("planRevision", 1));

        ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.times(2)).send(frames.capture());
        org.junit.jupiter.api.Assertions.assertTrue(frameData(frames.getAllValues().get(0)).contains("planRevision"));
        org.junit.jupiter.api.Assertions.assertTrue(frameData(frames.getAllValues().get(1)).contains("after plan"));
    }

    private AgentRunEvent event(long sequence, String type, String payload) {
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(71L);
        event.setSequenceNumber(sequence);
        event.setEventType(type);
        event.setPayload(payload);
        return event;
    }
    private String frameData(SseEmitter.SseEventBuilder builder) {
        return builder.build().stream()
                .map(item -> String.valueOf(item.getData()))
                .collect(java.util.stream.Collectors.joining());
    }
    @Test
    void preservesACallerSuppliedStableIdempotencyKeyForFinalizationBlockers() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        ExecutionFence fence = new ExecutionFence(71L, "worker-a", 4L);
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(43L);
        String key = "finalization-blocked-71-4-evidence-a-recovery-1";
        when(lifecycle.appendEvent(eq(fence), eq(71L), eq("FINALIZATION_BLOCKED"), any(), eq(key)))
                .thenReturn(event);

        AgentSsePublisher publisher = new AgentSsePublisher(emitter);
        publisher.bindRun(lifecycle, 71L, fence);
        publisher.send("FINALIZATION_BLOCKED", Map.of("reasonCode", "preview_url_mismatch"), key);

        verify(lifecycle).appendEvent(eq(fence), eq(71L), eq("FINALIZATION_BLOCKED"), any(), eq(key));
    }

}
