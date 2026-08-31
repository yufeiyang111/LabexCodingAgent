package com.labex.labexagent.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.AgentConversation;
import com.labex.labexagent.service.AgentConversationHistoryProjectionService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 会话派生数据热缓存。
 *
 * <p>MySQL/durable projection 是唯一事实源；Redis 读失败、反序列化失败或关闭时必须回源。</p>
 */
@Component
public class ConversationHotCache {
    private static final Logger log = LoggerFactory.getLogger(ConversationHotCache.class);
    private static final String SCHEMA_VERSION = "conversation-hot-v1";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ConversationCacheProperties properties;
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "conversation-cache-double-delete");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public ConversationHotCache(StringRedisTemplate redis, ObjectMapper objectMapper,
                                ConversationCacheProperties properties) {
        this.redis = Objects.requireNonNull(redis, "redis is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    public List<AgentConversation> getConversationList(Integer studentId, Integer projectId,
                                                         Supplier<List<AgentConversation>> loader) {
        return readOrLoad(listKey(studentId, projectId), properties.getListTtl(),
                new TypeReference<List<AgentConversation>>() {}, loader);
    }

    public AgentConversationHistoryProjectionService.HistoryPage getFirstHistoryPage(
            Integer studentId, Integer projectId, String conversationId,
            Supplier<AgentConversationHistoryProjectionService.HistoryPage> loader) {
        return readOrLoad(historyKey(studentId, projectId, conversationId), properties.getHistoryTtl(),
                new TypeReference<AgentConversationHistoryProjectionService.HistoryPage>() {}, loader);
    }

    public Map<String, Object> getContextStatus(Integer studentId, Integer projectId, String conversationId,
                                                  Supplier<Map<String, Object>> loader) {
        return readOrLoad(contextKey(studentId, projectId, conversationId), properties.getContextStatusTtl(),
                new TypeReference<Map<String, Object>>() {}, loader);
    }

    public void invalidateConversation(Integer studentId, Integer projectId, String conversationId) {
        deleteAfterCommit(listKey(studentId, projectId), historyKey(studentId, projectId, conversationId),
                contextKey(studentId, projectId, conversationId));
    }

    public void invalidateProject(Integer studentId, Integer projectId) {
        deleteAfterCommit(listKey(studentId, projectId));
    }

    private <T> T readOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        if (!properties.isEnabled()) return loader.get();
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                Envelope envelope = objectMapper.readValue(cached, Envelope.class);
                if (SCHEMA_VERSION.equals(envelope.version())) {
                    return objectMapper.readValue(envelope.data(), type);
                }
                deleteQuietly(key);
            }
        } catch (Exception failure) {
            log.debug("conversation cache read fallback key={}", key, failure);
            deleteQuietly(key);
        }

        T loaded = loader.get();
        if (loaded == null) return null;
        try {
            String data = objectMapper.writeValueAsString(loaded);
            String envelope = objectMapper.writeValueAsString(new Envelope(SCHEMA_VERSION, data));
            redis.opsForValue().set(key, envelope, ttl);
        } catch (Exception failure) {
            log.debug("conversation cache write skipped key={}", key, failure);
        }
        return loaded;
    }

    private void deleteAfterCommit(String... keys) {
        if (!properties.isEnabled() || keys == null || keys.length == 0) return;
        for (String key : keys) deleteQuietly(key);
        Runnable secondDelete = () -> {
            for (String key : keys) deleteQuietly(key);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(secondDelete);
                }
            });
        } else {
            schedule(secondDelete);
        }
    }

    private void schedule(Runnable task) {
        scheduler.schedule(task, Math.max(0L, properties.getDoubleDeleteDelayMs()),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException failure) {
            log.debug("conversation cache delete skipped key={}", key, failure);
        }
    }

    private String listKey(Integer studentId, Integer projectId) {
        return properties.getKeyPrefix() + ":list:" + studentId + ":" + projectId;
    }

    private String historyKey(Integer studentId, Integer projectId, String conversationId) {
        return properties.getKeyPrefix() + ":history:first:" + studentId + ":" + projectId + ":" + conversationId;
    }

    private String contextKey(Integer studentId, Integer projectId, String conversationId) {
        return properties.getKeyPrefix() + ":context-status:" + studentId + ":" + projectId + ":" + conversationId;
    }

    private record Envelope(String version, String data) { }
}
