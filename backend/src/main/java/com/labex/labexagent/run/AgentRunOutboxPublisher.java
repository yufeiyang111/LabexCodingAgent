package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentRunOutbox;
import com.labex.mapper.AgentRunOutboxMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AgentRunOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(AgentRunOutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final AgentRunOutboxMapper outboxMapper;
    private final AgentRunOutboxSink sink;

    public AgentRunOutboxPublisher(AgentRunOutboxMapper outboxMapper, AgentRunOutboxSink sink) {
        this.outboxMapper = outboxMapper;
        this.sink = sink;
    }

    @Scheduled(fixedDelayString = "${labex.agent.outbox-poll-interval-ms:1000}")
    public void publishScheduled() {
        publishAvailable();
    }

    public int publishAvailable() {
        LocalDateTime now = LocalDateTime.now();
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
            if (!claim(outbox, now)) {
                continue;
            }
            try {
                sink.publish(outbox);
                markPublished(outbox);
                published++;
            } catch (Exception e) {
                scheduleRetry(outbox, e);
            }
        }
        return published;
    }

    private boolean claim(AgentRunOutbox outbox, LocalDateTime now) {
        int attempts = valueOrZero(outbox.getAttempts()) + 1;
        int updated = outboxMapper.update(null, new UpdateWrapper<AgentRunOutbox>()
                .eq("outbox_id", outbox.getOutboxId())
                .eq("status", "pending")
                .le("available_time", now)
                .set("status", "publishing")
                .set("attempts", attempts));
        if (updated != 1) {
            return false;
        }
        outbox.setStatus("publishing");
        outbox.setAttempts(attempts);
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
