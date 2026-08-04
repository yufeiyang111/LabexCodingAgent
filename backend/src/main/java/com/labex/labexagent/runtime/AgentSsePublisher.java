package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.dto.AgentEvent;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.labexagent.run.AgentRunLifecycleService;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class AgentSsePublisher {
    private static final Gson GSON = new Gson();
    private final SseEmitter emitter;
    private final TransientEventListener transientEventListener;
    private AgentRunLifecycleService lifecycleService;
    private Long taskId;
    private long lastProjectedSequence;
    private boolean connectionClosed;

    public AgentSsePublisher(SseEmitter emitter) {
        this(emitter, null);
    }

    public AgentSsePublisher(SseEmitter emitter, TransientEventListener transientEventListener) {
        this.emitter = emitter;
        this.transientEventListener = transientEventListener;
    }

    public void bindRun(AgentRunLifecycleService lifecycleService, Long taskId) {
        if (lifecycleService == null || taskId == null) {
            throw new IllegalArgumentException("A lifecycle service and task ID are required to bind an agent SSE stream");
        }
        this.lifecycleService = lifecycleService;
        this.taskId = taskId;
        // bind 之前的事件由独立 replay/subscription 入口负责；当前连接只补齐 bind 之后出现的缺口。
        this.lastProjectedSequence = Math.max(0L, lifecycleService.currentEventSequence(taskId));
    }

    /** 当前 SSE 是否已绑定到可持久化的 Agent run。 */
    public boolean isBound() {
        return this.lifecycleService != null && this.taskId != null;
    }

    public synchronized void send(String type, Object data) throws IOException {
        if (this.lifecycleService == null || this.taskId == null) {
            throw new IllegalStateException(
                    "Durable SSE events require a bound agent run before they can be sent");
        }
        Object safeData = InternalReasoningBoundary.sanitizeEventPayload(type, data);
        AgentRunEvent event = this.lifecycleService.appendEvent(
                this.taskId,
                type,
                safeData,
                "sse-" + this.taskId + "-" + UUID.randomUUID());
        if (event == null || event.getSequenceNumber() == null) {
            throw new IllegalStateException("Durable SSE persistence returned no event sequence");
        }
        if (this.connectionClosed) {
            return;
        }
        try {
            projectPersistedThrough(event.getSequenceNumber(), type, safeData);
        } catch (IOException ignored) {
            this.connectionClosed = true;
        }
    }

    public void sendTransient(String type, Object data) throws IOException {
        Object safeData = InternalReasoningBoundary.sanitizeEventPayload(type, data);
        if (this.transientEventListener != null && this.taskId != null) {
            try {
                this.transientEventListener.publish(this.taskId, type, safeData);
            } catch (RuntimeException ignored) {
                // A secondary observer must never disrupt the primary Agent stream.
            }
        }
        if (this.connectionClosed) {
            return;
        }
        try {
            this.sendFrame(null, type, safeData);
        } catch (IOException e) {
            this.connectionClosed = true;
            throw e;
        }
    }

    @FunctionalInterface
    public interface TransientEventListener {
        void publish(Long taskId, String type, Object data);
    }

    public synchronized void sendPersisted(Long sequenceNumber, String type, Object data) throws IOException {
        Object safeData = InternalReasoningBoundary.sanitizeEventPayload(type, data);
        if (!isBound()) {
            this.sendFrame(sequenceNumber, type, safeData);
            return;
        }
        if (sequenceNumber == null || sequenceNumber <= 0L || sequenceNumber <= lastProjectedSequence) {
            return;
        }
        projectPersistedThrough(sequenceNumber, type, safeData);
    }

    /**
     * 先补齐数据库中已经提交但当前连接尚未观察到的事件，再投影目标 sequence。
     * 这保证工具事务内事件不会被外围后续事件越过并被前端单调游标丢弃。
     */
    private void projectPersistedThrough(long targetSequence, String fallbackType, Object fallbackData)
            throws IOException {
        if (connectionClosed || targetSequence <= lastProjectedSequence) {
            return;
        }
        int batches = 0;
        while (lastProjectedSequence < targetSequence && batches++ < 32) {
            List<AgentRunEvent> events = lifecycleService.eventsAfter(
                    taskId, lastProjectedSequence, targetSequence);
            if (events == null || events.isEmpty()) {
                break;
            }
            long before = lastProjectedSequence;
            List<AgentRunEvent> ordered = events.stream()
                    .filter(event -> event != null && event.getSequenceNumber() != null)
                    .sorted(Comparator.comparing(AgentRunEvent::getSequenceNumber))
                    .toList();
            for (AgentRunEvent event : ordered) {
                long sequence = event.getSequenceNumber();
                if (sequence <= lastProjectedSequence || sequence > targetSequence || connectionClosed) {
                    continue;
                }
                String eventType = event.getEventType() == null || event.getEventType().isBlank()
                        ? fallbackType : event.getEventType();
                Object payload = durablePayload(event, eventType);
                try {
                    sendFrame(sequence, eventType, payload);
                    lastProjectedSequence = sequence;
                } catch (IOException error) {
                    connectionClosed = true;
                    throw error;
                }
            }
            if (lastProjectedSequence == before) {
                break;
            }
        }
        if (lastProjectedSequence < targetSequence) {
            sendFrame(targetSequence, fallbackType, fallbackData);
            lastProjectedSequence = targetSequence;
        }
    }

    private Object durablePayload(AgentRunEvent event, String eventType) {
        if (event.getPayload() == null || event.getPayload().isBlank()) {
            return Map.of();
        }
        try {
            Object decoded = GSON.fromJson(event.getPayload(), Object.class);
            return InternalReasoningBoundary.sanitizeEventPayload(eventType, decoded);
        } catch (RuntimeException ignored) {
            return Map.of("message", "Stored Agent event payload is unavailable");
        }
    }

    private void sendFrame(Long sequenceNumber, String type, Object data) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(type)
                .data(GSON.toJson(new AgentEvent(type, data)));
        if (sequenceNumber != null) {
            event.id(String.valueOf(sequenceNumber));
        }
        this.emitter.send(event);
    }
}
