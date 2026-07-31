package com.labex.labexagent.run;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.runtime.AgentSsePublisher;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keeps browser SSE connections separate from Agent execution. Events are first replayed from
 * durable storage, then committed outbox messages are fanned out to subscribers of the same task.
 * Disconnecting a subscriber only removes that observer; it never requests task cancellation.
 */
@Service
public class AgentTaskEventSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(AgentTaskEventSubscriptionService.class);
    private static final String EVENT_TOPIC = "agent.run.event";
    private static final Gson GSON = new Gson();

    private final AgentRunEventReplayService replayService;
    private final ConcurrentHashMap<Long, CopyOnWriteArraySet<Subscription>> subscriptions = new ConcurrentHashMap<>();

    public AgentTaskEventSubscriptionService(AgentRunEventReplayService replayService) {
        this.replayService = replayService;
    }

    public SseEmitter subscribe(Integer studentId, Integer projectId, Long taskId, long afterSequence, SseEmitter emitter) {
        if (emitter == null) {
            throw new IllegalArgumentException("SSE emitter is required");
        }
        long cursor = Math.max(0L, afterSequence);
        long replayStartedAt = System.nanoTime();
        log.info("TASK_EVENT_SUBSCRIBE_REQUEST studentId={} projectId={} taskId={} afterSequence={}",
                studentId, projectId, taskId, cursor);
        Subscription subscription = new Subscription(studentId, projectId, taskId, emitter, cursor);
        CopyOnWriteArraySet<Subscription> taskSubscriptions = subscriptions.computeIfAbsent(taskId, ignored -> new CopyOnWriteArraySet<>());
        taskSubscriptions.add(subscription);
        log.info("TASK_EVENT_SUBSCRIBER_REGISTERED taskId={} afterSequence={} subscriberCount={}",
                taskId, cursor, taskSubscriptions.size());
        emitter.onCompletion(() -> remove(subscription, "client_complete"));
        emitter.onTimeout(() -> remove(subscription, "client_timeout"));
        emitter.onError(ignored -> remove(subscription, "client_error"));

        try {
            int replayed = subscription.catchUp();
            long replayMs = (System.nanoTime() - replayStartedAt) / 1_000_000L;
            log.info("TASK_EVENT_REPLAY_COMPLETE taskId={} afterSequence={} replayed={} lastSequence={} replayMs={}",
                    taskId, cursor, replayed, subscription.lastSequence(), replayMs);
        } catch (RuntimeException exception) {
            log.warn("TASK_EVENT_REPLAY_FAILED taskId={} afterSequence={} errorType={}",
                    taskId, cursor, exception.getClass().getSimpleName());
            remove(subscription, "replay_failed");
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    /** Broadcasts a live model delta to currently connected observers only; it is intentionally not replayable. */
    public void publishTransient(Long taskId, String eventType, Object eventPayload) {
        if (taskId == null || eventType == null || eventType.isBlank()) {
            return;
        }
        CopyOnWriteArraySet<Subscription> taskSubscriptions = subscriptions.get(taskId);
        if (taskSubscriptions == null) {
            return;
        }
        log.debug("TASK_EVENT_TRANSIENT_BROADCAST taskId={} eventType={} subscriberCount={}",
                taskId, eventType, taskSubscriptions.size());
        for (Subscription subscription : taskSubscriptions) {
            subscription.sendTransient(eventType, eventPayload);
        }
    }

    /**
     * 每个订阅实例都从数据库事件日志追赶自己的游标，避免 outbox 被其他 JVM 消费后本机 SSE 永久丢事件。
     */
    @Scheduled(fixedDelayString = "${labex.agent.task-event-poll-ms:250}")
    public void pollDurableEvents() {
        for (CopyOnWriteArraySet<Subscription> taskSubscriptions : subscriptions.values()) {
            for (Subscription subscription : taskSubscriptions) {
                try {
                    subscription.catchUp();
                } catch (RuntimeException exception) {
                    log.warn("TASK_EVENT_DURABLE_POLL_FAILED taskId={} afterSequence={} errorType={}",
                            subscription.taskId, subscription.lastSequence(), exception.getClass().getSimpleName());
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${labex.agent.task-event-heartbeat-ms:15000}")
    public void sendHeartbeats() {
        int subscriberCount = subscriptions.values().stream().mapToInt(CopyOnWriteArraySet::size).sum();
        log.debug("TASK_EVENT_HEARTBEAT subscribers={} taskCount={}", subscriberCount, subscriptions.size());
        for (CopyOnWriteArraySet<Subscription> taskSubscriptions : subscriptions.values()) {
            for (Subscription subscription : taskSubscriptions) {
                subscription.heartbeat();
            }
        }
    }

    @EventListener
    public void onApplicationEvent(AgentRunOutboxMessage message) {
        if (message == null || !EVENT_TOPIC.equals(message.topic()) || message.taskId() == null) {
            return;
        }
        try {
            JsonObject envelope = JsonParser.parseString(message.payload()).getAsJsonObject();
            long sequence = envelope.get("sequence").getAsLong();
            String eventType = envelope.get("eventType").getAsString();
            String state = envelope.has("state") ? envelope.get("state").getAsString() : "";
            Object payload = envelope.has("payload") ? GSON.fromJson(envelope.get("payload"), Object.class) : Map.of();
            CopyOnWriteArraySet<Subscription> taskSubscriptions = subscriptions.get(message.taskId());
            int subscriberCount = taskSubscriptions == null ? 0 : taskSubscriptions.size();
            log.info("TASK_EVENT_OUTBOX_BROADCAST taskId={} sequence={} eventType={} state={} subscriberCount={}",
                    message.taskId(), sequence, eventType, state, subscriberCount);
            if (taskSubscriptions == null) {
                return;
            }
            for (Subscription subscription : taskSubscriptions) {
                subscription.send(sequence, eventType, payload, isTerminalState(state));
            }
        } catch (RuntimeException exception) {
            log.warn("TASK_EVENT_OUTBOX_MALFORMED taskId={} eventId={} errorType={}",
                    message.taskId(), message.eventId(), exception.getClass().getSimpleName());
            // A malformed external event must not terminate unrelated task streams.
        }
    }

    private Object payload(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonElement value = JsonParser.parseString(raw);
            return GSON.fromJson(value, Object.class);
        } catch (RuntimeException ignored) {
            return Map.of("message", raw);
        }
    }

    private boolean isTerminalState(String state) {
        return "completed".equalsIgnoreCase(state)
                || "failed".equalsIgnoreCase(state)
                || "cancelled".equalsIgnoreCase(state);
    }

    private void remove(Subscription subscription, String reason) {
        CopyOnWriteArraySet<Subscription> taskSubscriptions = subscriptions.get(subscription.taskId);
        if (taskSubscriptions == null || !taskSubscriptions.remove(subscription)) {
            return;
        }
        int remaining = taskSubscriptions.size();
        if (taskSubscriptions.isEmpty()) {
            subscriptions.remove(subscription.taskId, taskSubscriptions);
        }
        log.info("TASK_EVENT_SUBSCRIBER_REMOVED taskId={} reason={} remainingSubscribers={}",
                subscription.taskId, reason, remaining);
    }

    private final class Subscription {
        private final Integer studentId;
        private final Integer projectId;
        private final Long taskId;
        private final SseEmitter emitter;
        private final AgentSsePublisher publisher;
        private final AtomicLong lastSequence;

        private Subscription(Integer studentId, Integer projectId, Long taskId,
                             SseEmitter emitter, long afterSequence) {
            this.studentId = studentId;
            this.projectId = projectId;
            this.taskId = taskId;
            this.emitter = emitter;
            this.publisher = new AgentSsePublisher(emitter);
            this.lastSequence = new AtomicLong(afterSequence);
        }

        private long lastSequence() {
            return lastSequence.get();
        }

        private synchronized int catchUp() {
            int delivered = 0;
            List<AgentRunEvent> events = replayService.eventsAfter(
                    studentId, projectId, taskId, lastSequence.get());
            for (AgentRunEvent event : events) {
                if (!sendPersisted(event.getSequenceNumber(), event.getEventType(),
                        payload(event.getPayload()), isTerminalState(event.getState()))) {
                    break;
                }
                delivered++;
                if (isTerminalState(event.getState())) {
                    break;
                }
            }
            return delivered;
        }

        private void heartbeat() {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException exception) {
                remove(this, "heartbeat_send_failed");
            }
        }

        private void sendTransient(String eventType, Object eventPayload) {
            try {
                publisher.sendTransient(eventType, eventPayload == null ? Map.of() : eventPayload);
            } catch (IOException exception) {
                remove(this, "transient_send_failed");
            }
        }

        private synchronized void send(Long sequenceNumber, String eventType, Object eventPayload, boolean terminal) {
            if (sequenceNumber == null || eventType == null || eventType.isBlank()) {
                return;
            }
            long sequence = sequenceNumber;
            if (sequence <= lastSequence.get()) {
                return;
            }
            if (sequence > lastSequence.get() + 1L) {
                catchUp();
            }
            if (sequence <= lastSequence.get()) {
                return;
            }
            if (sequence != lastSequence.get() + 1L) {
                log.info("TASK_EVENT_OUTBOX_DEFERRED_FOR_GAP taskId={} expectedSequence={} receivedSequence={}",
                        taskId, lastSequence.get() + 1L, sequence);
                return;
            }
            sendPersisted(sequenceNumber, eventType, eventPayload, terminal);
        }

        private boolean sendPersisted(Long sequenceNumber, String eventType, Object eventPayload, boolean terminal) {
            if (sequenceNumber == null || eventType == null || eventType.isBlank()) {
                return false;
            }
            long sequence = sequenceNumber;
            long previous = lastSequence.get();
            if (sequence <= previous) {
                return true;
            }
            if (sequence != previous + 1L) {
                log.info("TASK_EVENT_DURABLE_GAP taskId={} expectedSequence={} receivedSequence={}",
                        taskId, previous + 1L, sequence);
                return false;
            }
            try {
                publisher.sendPersisted(sequence, eventType, eventPayload == null ? Map.of() : eventPayload);
                lastSequence.set(sequence);
                if (terminal) {
                    remove(this, "terminal_state");
                    emitter.complete();
                }
                return true;
            } catch (IOException exception) {
                remove(this, "durable_send_failed");
                return false;
            }
        }
    }
}
