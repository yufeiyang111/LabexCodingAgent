package com.labex.labexagent.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class WebSearchCacheTest {

    @Test
    void cachesAndRetrievesSearchResultsWithAllRequestParametersAndScope() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheTtlSeconds(60);
        WebSearchCache cache = cache(properties, mockRedis(null));

        WebSearchRequest request = new WebSearchRequest("spring boot 3", 10, "preferred", "deep", 4000);
        WebSearchResponse response = new WebSearchResponse(WebSearchProviderId.TAVILY, "1. Result\nurl: https://test.com");
        cache.put(WebSearchProviderId.TAVILY, request, context(7, 3), response);

        assertNotNull(cache.get(WebSearchProviderId.TAVILY, request, context(7, 3)));
        assertNull(cache.get(WebSearchProviderId.TAVILY,
                new WebSearchRequest("spring boot 3", 10, "preferred", "deep", 4001), context(7, 3)));
        assertNull(cache.get(WebSearchProviderId.TAVILY,
                new WebSearchRequest("spring boot 3", 10, "fallback", "deep", 4000), context(7, 3)));
        assertNull(cache.get(WebSearchProviderId.TAVILY,
                new WebSearchRequest("spring boot 3", 10, "preferred", "fast", 4000), context(7, 3)));
        assertNull(cache.get(WebSearchProviderId.TAVILY,
                new WebSearchRequest("Spring boot 3", 10, "preferred", "deep", 4000), context(7, 3)));
        assertNull(cache.get(WebSearchProviderId.TAVILY, request, context(8, 3)));
        assertNull(cache.get(WebSearchProviderId.TAVILY, request, context(7, 4)));
        assertNull(cache.get(WebSearchProviderId.TAVILY,
                new WebSearchRequest("spring boot 3", 11, "preferred", "deep", 4000), context(7, 3)));
        assertNotNull(cache.get(WebSearchProviderId.TAVILY, request, context(7, 3)));
    }

    @Test
    void rejectsCacheAccessWithoutOwnedProjectContext() {
        WebSearchProperties properties = new WebSearchProperties();
        WebSearchCache cache = cache(properties, mockRedis(null));
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);

        cache.put(WebSearchProviderId.EXA, request, context(null, 3),
                new WebSearchResponse(WebSearchProviderId.EXA, "must not cache"));
        assertNull(cache.get(WebSearchProviderId.EXA, request, context(null, 3)));

        cache.put(WebSearchProviderId.EXA, request, context(7, 3),
                new WebSearchResponse(WebSearchProviderId.EXA, "must not cache"));
        assertNull(cache.get(WebSearchProviderId.EXA, request, context(8, 3)));
        assertNull(cache.get(WebSearchProviderId.EXA, request, contextWithProjectOwner(7, 3, 8)));
        assertNotNull(cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
        assertNull(cache.get(WebSearchProviderId.EXA, request, null));
    }

    @Test
    void redisHitPopulatesL1AndUsesActualProviderInVersionedHashedKey() throws Exception {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheTtlSeconds(90);
        StringRedisTemplate redis = mockRedis(null);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ObjectMapper mapper = new ObjectMapper();
        String value = mapper.writeValueAsString(new WebSearchResponse(WebSearchProviderId.EXA, "shared result"));
        when(values.get(anyString())).thenReturn(value);
        WebSearchCache cache = cache(properties, redis, mapper);
        WebSearchRequest request = new WebSearchRequest("secret query text", 5, "fallback", "auto", null);

        WebSearchResponse first = cache.get(WebSearchProviderId.EXA, request, context(7, 3));
        WebSearchResponse second = cache.get(WebSearchProviderId.EXA, request, context(7, 3));

        assertNotNull(first);
        assertEquals("shared result", second.content());
        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.times(1)).get(key.capture());
        org.junit.jupiter.api.Assertions.assertTrue(key.getValue().startsWith("labex:web-search:v1:7:3:exa:"));
        org.junit.jupiter.api.Assertions.assertFalse(key.getValue().contains("secret query text"));
        org.junit.jupiter.api.Assertions.assertTrue(key.getValue().matches(".*:[0-9a-f]{64}$"));
    }

    @Test
    void redisWriteFailureIsFailOpen() {
        WebSearchProperties properties = new WebSearchProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);
        WebSearchResponse expected = new WebSearchResponse(WebSearchProviderId.EXA, "fallback result");
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(values).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));

        cache.put(WebSearchProviderId.EXA, request, context(7, 3), expected);

        assertEquals(expected, cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
    }

    @Test
    void invalidRedisValueIsTreatedAsMissAndDeleted() {
        WebSearchProperties properties = new WebSearchProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{not-json");
        when(redis.delete(anyString())).thenReturn(true);
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);

        assertNull(cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
        verify(redis).delete(anyString());
    }

    @Test
    void providerMismatchIsTreatedAsMissAndDeleted() throws Exception {
        WebSearchProperties properties = new WebSearchProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(new ObjectMapper().writeValueAsString(
                new WebSearchResponse(WebSearchProviderId.TAVILY, "wrong provider")));
        when(redis.delete(anyString())).thenReturn(true);
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);

        assertNull(cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
        verify(redis).delete(anyString());
    }

    @Test
    void oversizedResponseDoesNotEnterEitherLayer() throws Exception {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheMaxValueBytes(16);
        StringRedisTemplate redis = mockRedis(null);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);
        WebSearchResponse response = new WebSearchResponse(WebSearchProviderId.EXA, "x".repeat(64));

        cache.put(WebSearchProviderId.EXA, request, context(7, 3), response);

        assertNull(cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
    }

    @Test
    void disabledL2KeepsL1AndDisabledGlobalCacheSkipsBoth() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheRedisEnabled(false);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);
        WebSearchResponse response = new WebSearchResponse(WebSearchProviderId.EXA, "local result");

        cache.put(WebSearchProviderId.EXA, request, context(7, 3), response);

        assertEquals(response, cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
        verify(redis, never()).opsForValue();

        properties.setCacheEnabled(false);
        assertNull(cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
    }

    @Test
    void ttlIsAppliedToRedisWrites() throws Exception {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheTtlSeconds(30);
        StringRedisTemplate redis = mockRedis(null);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);
        WebSearchResponse response = new WebSearchResponse(WebSearchProviderId.EXA, "ttl result");

        cache.put(WebSearchProviderId.EXA, request, context(7, 3), response);

        verify(values).set(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(30L), org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void parallelAlwaysBypassesBothLayers() {
        WebSearchProperties properties = new WebSearchProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);
        WebSearchResponse response = new WebSearchResponse(WebSearchProviderId.PARALLEL, "session result");

        cache.put(WebSearchProviderId.PARALLEL, request, context(7, 3), response);

        assertNull(cache.get(WebSearchProviderId.PARALLEL, request, context(7, 3)));
        verify(redis, never()).opsForValue();
        verify(redis, never()).delete(anyString());
    }

    @Test
    void providerKeyIsStableUnderTurkishLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            WebSearchProperties properties = new WebSearchProperties();
            StringRedisTemplate redis = mockRedis(null);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            WebSearchCache cache = cache(properties, redis);
            WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);

            cache.put(WebSearchProviderId.TAVILY, request, context(7, 3),
                    new WebSearchResponse(WebSearchProviderId.TAVILY, "result"));

            org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(values).set(key.capture(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
            org.junit.jupiter.api.Assertions.assertTrue(key.getValue().contains(":tavily:"));
            org.junit.jupiter.api.Assertions.assertFalse(key.getValue().contains(":tavİly:"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void expiredL1EntryFallsThroughToRedis() throws Exception {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheTtlSeconds(1);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(new ObjectMapper().writeValueAsString(
                new WebSearchResponse(WebSearchProviderId.EXA, "redis result")));
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);

        cache.put(WebSearchProviderId.EXA, request, context(7, 3),
                new WebSearchResponse(WebSearchProviderId.EXA, "expired l1"));
        Thread.sleep(1_100L);
        WebSearchResponse response = cache.get(WebSearchProviderId.EXA, request, context(7, 3));

        assertEquals("redis result", response.content());
        verify(values).get(anyString());
    }

    @Test
    void lruEvictsLeastRecentlyUsedEntryWhenCapacityIsReached() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheRedisEnabled(false);
        properties.setCacheMaxEntries(10);
        WebSearchCache cache = cache(properties, mock(StringRedisTemplate.class));

        for (int index = 0; index < 10; index++) {
            cache.put(WebSearchProviderId.EXA, request(index), context(7, 3),
                    new WebSearchResponse(WebSearchProviderId.EXA, "result-" + index));
        }
        assertNotNull(cache.get(WebSearchProviderId.EXA, request(0), context(7, 3)));

        cache.put(WebSearchProviderId.EXA, request(10), context(7, 3),
                new WebSearchResponse(WebSearchProviderId.EXA, "result-10"));

        assertNotNull(cache.get(WebSearchProviderId.EXA, request(0), context(7, 3)));
        assertNull(cache.get(WebSearchProviderId.EXA, request(1), context(7, 3)));
        assertNotNull(cache.get(WebSearchProviderId.EXA, request(10), context(7, 3)));
    }

    @Test
    void deleteFailureForCorruptValueStillReturnsMiss() {
        WebSearchProperties properties = new WebSearchProperties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{not-json");
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("delete down"));
        WebSearchCache cache = cache(properties, redis);
        WebSearchRequest request = new WebSearchRequest("query", 5, "fallback", "auto", null);

        assertNull(cache.get(WebSearchProviderId.EXA, request, context(7, 3)));
        verify(redis).delete(anyString());
    }

    private WebSearchRequest request(int index) {
        return new WebSearchRequest("query-" + index, 5, "fallback", "auto", null);
    }

    private WebSearchCache cache(WebSearchProperties properties, StringRedisTemplate redis) {
        return cache(properties, redis, new ObjectMapper());
    }

    private WebSearchCache cache(WebSearchProperties properties, StringRedisTemplate redis, ObjectMapper mapper) {
        return new WebSearchCache(properties, redis, mapper);
    }

    private StringRedisTemplate mockRedis(String value) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(value);
        return redis;
    }

    private AgentContext contextWithProjectOwner(Integer studentId, Integer projectId, Integer projectOwnerId) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(projectOwnerId);
        return new AgentContext("session", studentId, project, "conversation", 1L,
                null, List.of(), 0);
    }

    private AgentContext context(Integer studentId, Integer projectId) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        return new AgentContext("session", studentId, project, "conversation", 1L,
                null, List.of(), 0);
    }
}
