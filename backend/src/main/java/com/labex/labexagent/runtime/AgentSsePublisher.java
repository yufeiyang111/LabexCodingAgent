package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.dto.AgentEvent;
import com.labex.labexagent.run.AgentRunLifecycleService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class AgentSsePublisher {
    private static final Gson GSON = new Gson();
    private final SseEmitter emitter;
    private final TransientEventListener transientEventListener;
    private AgentRunLifecycleService lifecycleService;
    private Long taskId;
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
    }

    public void send(String type, Object data) throws IOException {
        if (this.lifecycleService == null || this.taskId == null) {
            this.send(null, type, data);
            return;
        }
        AgentRunEvent event = this.lifecycleService.appendEvent(
                this.taskId,
                type,
                data,
                "sse-" + this.taskId + "-" + UUID.randomUUID());
        if (this.connectionClosed) {
            return;
        }
        try {
            this.send(event.getSequenceNumber(), type, data);
        } catch (IOException ignored) {
            this.connectionClosed = true;
        }
    }

    public void sendTransient(String type, Object data) throws IOException {
        if (this.transientEventListener != null && this.taskId != null) {
            try {
                this.transientEventListener.publish(this.taskId, type, data);
            } catch (RuntimeException ignored) {
                // A secondary observer must never disrupt the primary Agent stream.
            }
        }
        if (this.connectionClosed) {
            return;
        }
        try {
            this.send(null, type, data);
        } catch (IOException e) {
            this.connectionClosed = true;
            throw e;
        }
    }

    @FunctionalInterface
    public interface TransientEventListener {
        void publish(Long taskId, String type, Object data);
    }

    public void send(Long sequenceNumber, String type, Object data) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name(type)
                .data(GSON.toJson(new AgentEvent(type, data)));
        if (sequenceNumber != null) {
            event.id(String.valueOf(sequenceNumber));
        }
        this.emitter.send(event);
    }
}
