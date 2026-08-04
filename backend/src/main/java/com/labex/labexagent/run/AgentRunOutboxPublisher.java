package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunOutbox;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AgentRunOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(AgentRunOutboxPublisher.class);
    private static final Gson GSON = new Gson();
    private static final int BATCH_SIZE = 100;
    private static final int CLAIM_TIMEOUT_SECONDS = 30;

    private final AgentRunOutboxMapper outboxMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentRunPartService partService;
    private final AgentRunOutboxSink sink;

    public AgentRunOutboxPublisher(AgentRunOutboxMapper outboxMapper,
                                   AgentRunEventMapper eventMapper,
                                   AgentRunPartService partService,
                                   AgentRunOutboxSink sink) {
        this.outboxMapper = outboxMapper;
        this.eventMapper = eventMapper;
        this.partService = partService;
        this.sink = sink;
    }

    @Scheduled(fixedDelayString = "${labex.agent.outbox-poll-interval-ms:1000}")
    public void publishScheduled() {
        publishAvailable();
    }

    public int publishAvailable() {
        LocalDateTime now = LocalDateTime.now();
        recoverAbandonedClaims(now);
        List<AgentRunOutbox> messages = outboxMapper.selectList(new QueryWrapper<AgentRunOutbox>()
                .eq("status", "pending")
                .le("available_time", now)
                .orderByAsc("available_time")
                .last("LIMIT " + BATCH_SIZE));
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (AgentRunOutbox outbox : messages) {
            int attemptsBeforeClaim = valueOrZero(outbox.getAttempts());
            LocalDateTime availableBeforeClaim = outbox.getAvailableTime();
            if (!claim(outbox, now)) {
                continue;
            }
            try {
                AgentRunEvent event = authoritativeEvent(outbox);
                if (hasUnpublishedPredecessor(event)) {
                    deferForPredecessor(outbox, attemptsBeforeClaim, availableBeforeClaim, now);
                    continue;
                }
                projectAuthoritativeEvent(event);
                sink.publish(outbox);
                markPublished(outbox);
                published++;
            } catch (Exception e) {
                scheduleRetry(outbox, e);
            }
        }
        return published;
    }

    private AgentRunEvent authoritativeEvent(AgentRunOutbox outbox) {
        if (outbox.getEventId() == null) {
            throw new IllegalStateException("Agent run outbox message has no authoritative eventId");
        }
        AgentRunEvent event = eventMapper.selectById(outbox.getEventId());
        if (event == null) {
            throw new IllegalStateException("Authoritative agent run event does not exist: " + outbox.getEventId());
        }
        if (!Objects.equals(outbox.getTaskId(), event.getTaskId())) {
            throw new IllegalStateException("Agent run outbox task identity does not match its authoritative event");
        }
        if (event.getTaskId() == null || event.getSequenceNumber() == null
                || event.getEventType() == null || event.getEventType().isBlank()) {
            throw new IllegalStateException("Authoritative agent run event is incomplete: " + outbox.getEventId());
        }
        return event;
    }

    /** 同一 task 的前驱事件尚未发布时，后续事件不能越过它进入 transcript 或实时投影。 */
    private boolean hasUnpublishedPredecessor(AgentRunEvent event) {
        return outboxMapper.countUnpublishedBeforeSequence(
                event.getTaskId(), event.getSequenceNumber()) > 0L;
    }

    private void deferForPredecessor(AgentRunOutbox outbox, int attemptsBeforeClaim,
                                     LocalDateTime availableBeforeClaim, LocalDateTime now) {
        outbox.setStatus("pending");
        outbox.setAttempts(attemptsBeforeClaim);
        outbox.setAvailableTime(availableBeforeClaim == null ? now : availableBeforeClaim);
        if (outboxMapper.updateById(outbox) != 1) {
            throw new IllegalStateException("Unable to release sequence-blocked agent run outbox message");
        }
        log.debug("Deferred agent run outbox message behind unpublished predecessor taskId={} eventId={}",
                outbox.getTaskId(), outbox.getEventId());
    }

    /**
     * outbox 是 Event 到 transcript 的持久化修复屏障。只有 Message/Part 已可重建，事件才允许对外广播。
     */
    private void projectAuthoritativeEvent(AgentRunEvent event) {
        partService.recordEventPart(
                event.getTaskId(),
                event.getEventType(),
                decodePayload(event),
                event.getSequenceNumber());
    }

    private Object decodePayload(AgentRunEvent event) {
        String payload = event.getPayload();
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            Object decoded = GSON.fromJson(payload, Object.class);
            return decoded == null ? Map.of() : decoded;
        } catch (JsonParseException error) {
            throw new IllegalStateException(
                    "Authoritative agent run event payload is not valid JSON: " + event.getEventId(), error);
        }
    }

    /** 进程在 claim 后崩溃时，租约到期的 publishing 记录必须重新进入现有重试队列。 */
    private void recoverAbandonedClaims(LocalDateTime now) {
        int recovered = outboxMapper.update(null, new UpdateWrapper<AgentRunOutbox>()
                .eq("status", "publishing")
                .le("available_time", now)
                .set("status", "pending")
                .set("available_time", now));
        if (recovered > 0) {
            log.warn("Recovered {} abandoned agent run outbox claim(s)", recovered);
        }
    }

    private boolean claim(AgentRunOutbox outbox, LocalDateTime now) {
        int attempts = valueOrZero(outbox.getAttempts()) + 1;
        LocalDateTime claimExpiresAt = now.plusSeconds(CLAIM_TIMEOUT_SECONDS);
        int updated = outboxMapper.update(null, new UpdateWrapper<AgentRunOutbox>()
                .eq("outbox_id", outbox.getOutboxId())
                .eq("status", "pending")
                .le("available_time", now)
                .set("status", "publishing")
                .set("attempts", attempts)
                .set("available_time", claimExpiresAt));
        if (updated != 1) {
            return false;
        }
        outbox.setStatus("publishing");
        outbox.setAttempts(attempts);
        outbox.setAvailableTime(claimExpiresAt);
        return true;
    }

    private void markPublished(AgentRunOutbox outbox) {
        outbox.setStatus("published");
        outbox.setPublishedTime(LocalDateTime.now());
        if (outboxMapper.updateById(outbox) != 1) {
            throw new IllegalStateException("Unable to mark agent run outbox message as published");
        }
    }

    private void scheduleRetry(AgentRunOutbox outbox, Exception error) {
        outbox.setStatus("pending");
        outbox.setAvailableTime(LocalDateTime.now().plusSeconds(retryDelaySeconds(valueOrZero(outbox.getAttempts()))));
        outboxMapper.updateById(outbox);
        log.warn("Agent run outbox delivery failed for outboxId={}, eventId={}: {}",
                outbox.getOutboxId(), outbox.getEventId(), error.getMessage());
    }

    private int retryDelaySeconds(int attempts) {
        return 1 << Math.min(Math.max(0, attempts), 6);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}