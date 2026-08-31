package com.labex.labexagent.cache;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.AgentConversation;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ConversationHotCacheTest {
    @Test
    void missesLoadFromDatabaseAndWriteBackToRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        ConversationCacheProperties properties = new ConversationCacheProperties();
        ConversationHotCache cache = new ConversationHotCache(redis,
                new ObjectMapper().findAndRegisterModules(), properties);
        List<AgentConversation> expected = List.of(new AgentConversation());

        List<AgentConversation> actual = cache.getConversationList(7, 3, () -> expected);

        assertSame(expected, actual);
        verify(values).set(anyString(), anyString(), any());
    }

    @Test
    void redisFailureFallsBackToDurableLoader() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        ConversationHotCache cache = new ConversationHotCache(redis,
                new ObjectMapper().findAndRegisterModules(), new ConversationCacheProperties());
        List<AgentConversation> expected = List.of(new AgentConversation());

        assertSame(expected, cache.getConversationList(7, 3, () -> expected));
    }

    @Test
    void invalidationDeletesImmediatelyAndAgainForDoubleDelete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ConversationCacheProperties properties = new ConversationCacheProperties();
        properties.setDoubleDeleteDelayMs(0);
        ConversationHotCache cache = new ConversationHotCache(redis,
                new ObjectMapper().findAndRegisterModules(), properties);

        cache.invalidateProject(7, 3);

        verify(redis, timeout(1000).times(2)).delete("labex:conversation:list:7:3");
    }
}
