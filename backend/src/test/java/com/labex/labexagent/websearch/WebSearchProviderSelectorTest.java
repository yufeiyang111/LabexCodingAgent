package com.labex.labexagent.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class WebSearchProviderSelectorTest {

    @Test
    void availabilityUsesTheSameConfiguredProviderRouteAsSearch() {
        WebSearchProperties properties = properties("auto");
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, false,
                new WebSearchResponse(WebSearchProviderId.EXA, "exa"));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback"));
        WebSearchProviderSelector selector = new WebSearchProviderSelector(properties, List.of(exa, fallback));

        assertTrue(selector.isAvailable());
        properties.setProvider("public_fallback");
        assertTrue(selector.isAvailable());
        assertEquals(0, exa.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    void autoPrefersTavilyWhenAvailable() throws Exception {
        WebSearchProperties properties = properties("auto");
        StubProvider tavily = new StubProvider(WebSearchProviderId.TAVILY, true,
                new WebSearchResponse(WebSearchProviderId.TAVILY, "tavily result"));
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "exa result"));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback result"));

        WebSearchResponse response = new WebSearchProviderSelector(properties, List.of(tavily, exa, fallback))
                .search(request(), context());

        assertEquals(WebSearchProviderId.TAVILY, response.provider());
        assertEquals(1, tavily.calls);
        assertEquals(0, exa.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    void autoFallsBackToPublicSearchOnlyAfterRecoverableExaFailure() throws Exception {
        WebSearchProperties properties = properties("auto");
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchException("temporary upstream failure", true));
        StubProvider parallel = new StubProvider(WebSearchProviderId.PARALLEL, true,
                new WebSearchResponse(WebSearchProviderId.PARALLEL, "parallel"));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback"));

        WebSearchResponse response = new WebSearchProviderSelector(properties, List.of(exa, parallel, fallback))
                .search(request(), context());

        assertEquals(WebSearchProviderId.PARALLEL, response.provider());
        assertEquals(1, exa.calls);
        assertEquals(1, parallel.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    void autoDoesNotFallBackAfterNonRecoverableExaFailure() {
        WebSearchProperties properties = properties("auto");
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchException("invalid provider request", false));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback"));

        WebSearchException error = assertThrows(WebSearchException.class,
                () -> new WebSearchProviderSelector(properties, List.of(exa, fallback)).search(request(), context()));

        assertFalse(error.isRecoverable());
        assertEquals(0, fallback.calls);
    }

    @Test
    void explicitParallelDoesNotFallBackToAnotherProvider() throws Exception {
        WebSearchProperties properties = properties("parallel");
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "exa"));
        StubProvider parallel = new StubProvider(WebSearchProviderId.PARALLEL, true,
                new WebSearchResponse(WebSearchProviderId.PARALLEL, "parallel"));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback"));

        WebSearchResponse response = new WebSearchProviderSelector(properties, List.of(exa, parallel, fallback))
                .search(request(), context());

        assertEquals(WebSearchProviderId.PARALLEL, response.provider());
        assertEquals(0, exa.calls);
        assertEquals(1, parallel.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    void autoChecksTavilyBeforeLowerPriorityCacheAndUsesActualProviderOnWrite() throws Exception {
        WebSearchProperties properties = properties("auto");
        StringRedisTemplate redis = mockRedis();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        StubProvider tavily = new StubProvider(WebSearchProviderId.TAVILY, true,
                new WebSearchResponse(WebSearchProviderId.TAVILY, "tavily result"));
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "exa result"));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback"));
        WebSearchProviderSelector selector = new WebSearchProviderSelector(properties,
                List.of(tavily, exa, fallback), cache);

        WebSearchResponse response = selector.search(request(), context());

        assertEquals(WebSearchProviderId.TAVILY, response.provider());
        assertEquals(1, tavily.calls);
        assertEquals(0, exa.calls);
        org.mockito.ArgumentCaptor<String> key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
        assertTrue(key.getValue().contains(":tavily:"));
        assertFalse(key.getValue().contains(":exa:"));
    }

    @Test
    void autoOnlyUsesLowerPriorityCacheAfterRecoverableFailure() throws Exception {
        WebSearchProperties properties = properties("auto");
        StringRedisTemplate redis = mockRedis();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        WebSearchRequest request = request();
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        cache.put(WebSearchProviderId.EXA, request, context(),
                new WebSearchResponse(WebSearchProviderId.EXA, "cached exa"));
        StubProvider tavily = new StubProvider(WebSearchProviderId.TAVILY, true,
                new WebSearchException("temporary tavily failure", true));
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "live exa"));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback"));

        WebSearchResponse response = new WebSearchProviderSelector(properties,
                List.of(tavily, exa, fallback), cache).search(request, context());

        assertEquals("cached exa", response.content());
        assertEquals(1, tavily.calls);
        assertEquals(0, exa.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    void autoDoesNotReadLowerPriorityCacheBeforeTavilyCanSucceed() throws Exception {
        WebSearchProperties properties = properties("auto");
        StringRedisTemplate redis = mockRedis();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        WebSearchRequest request = request();
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        cache.put(WebSearchProviderId.EXA, request, context(),
                new WebSearchResponse(WebSearchProviderId.EXA, "cached exa"));
        StubProvider tavily = new StubProvider(WebSearchProviderId.TAVILY, true,
                new WebSearchResponse(WebSearchProviderId.TAVILY, "live tavily"));
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "live exa"));

        WebSearchResponse response = new WebSearchProviderSelector(properties,
                List.of(tavily, exa), cache).search(request, context());

        assertEquals(WebSearchProviderId.TAVILY, response.provider());
        assertEquals(1, tavily.calls);
        assertEquals(0, exa.calls);
    }

    @Test
    void explicitProviderOnlyUsesItsOwnCache() throws Exception {
        WebSearchProperties properties = properties("exa");
        StringRedisTemplate redis = mockRedis();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        WebSearchRequest request = request();
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        cache.put(WebSearchProviderId.TAVILY, request, context(),
                new WebSearchResponse(WebSearchProviderId.TAVILY, "cached tavily"));
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "live exa"));
        StubProvider tavily = new StubProvider(WebSearchProviderId.TAVILY, true,
                new WebSearchResponse(WebSearchProviderId.TAVILY, "live tavily"));

        WebSearchResponse response = new WebSearchProviderSelector(properties,
                List.of(tavily, exa), cache).search(request, context());

        assertEquals(WebSearchProviderId.EXA, response.provider());
        assertEquals(0, tavily.calls);
        assertEquals(1, exa.calls);
    }

    @Test
    void parallelBypassesBothCacheLayersEvenWithSessionContext() throws Exception {
        WebSearchProperties properties = properties("parallel");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        StubProvider parallel = new StubProvider(WebSearchProviderId.PARALLEL, true,
                new WebSearchResponse(WebSearchProviderId.PARALLEL, "live parallel"));

        WebSearchResponse response = new WebSearchProviderSelector(properties, List.of(parallel), cache)
                .search(request(), context());

        assertEquals("live parallel", response.content());
        assertEquals(1, parallel.calls);
        verify(redis, never()).opsForValue();
        verify(redis, never()).delete(anyString());
    }

    @Test
    void missingOrMismatchedContextBypassesCacheButStillSearches() throws Exception {
        WebSearchProperties properties = properties("exa");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "live exa"));

        WebSearchResponse response = new WebSearchProviderSelector(properties, List.of(exa), cache)
                .search(request(), null);

        assertEquals("live exa", response.content());
        assertEquals(1, exa.calls);
        verify(redis, never()).opsForValue();
    }

    @Test
    void redisFailureDoesNotChangeProviderSuccess() throws Exception {
        WebSearchProperties properties = properties("exa");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "live exa"));

        WebSearchResponse response = new WebSearchProviderSelector(properties, List.of(exa), cache)
                .search(request(), context());

        assertNotNull(response);
        assertEquals("live exa", response.content());
        assertEquals(1, exa.calls);
    }

    @Test
    void redisFailureDoesNotChangeProviderErrorSemantics() {
        WebSearchProperties properties = properties("auto");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchException("nonrecoverable", false));
        StubProvider fallback = new StubProvider(WebSearchProviderId.PUBLIC_FALLBACK, true,
                new WebSearchResponse(WebSearchProviderId.PUBLIC_FALLBACK, "fallback"));

        WebSearchException error = assertThrows(WebSearchException.class,
                () -> new WebSearchProviderSelector(properties, List.of(exa, fallback), cache)
                        .search(request(), context()));

        assertFalse(error.isRecoverable());
        assertEquals(0, fallback.calls);
    }

    @Test
    void nullAndExceptionalProviderResultsAreNotCached() throws Exception {
        WebSearchProperties properties = properties("exa");
        StringRedisTemplate redis = mockRedis();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        StubProvider nullProvider = new StubProvider(WebSearchProviderId.EXA, true, (WebSearchResponse) null);
        WebSearchProviderSelector selector = new WebSearchProviderSelector(properties, List.of(nullProvider), cache);

        assertNull(selector.search(request(), context()));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));

        StubProvider failing = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchException("failed", false));
        assertThrows(WebSearchException.class,
                () -> new WebSearchProviderSelector(properties, List.of(failing), cache).search(request(), context()));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
    }

    @Test
    void corruptRedisValueWithDeleteFailureFallsBackToProvider() throws Exception {
        WebSearchProperties properties = properties("exa");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("{not-json");
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("delete down"));
        WebSearchCache cache = new WebSearchCache(properties, redis, new ObjectMapper());
        StubProvider exa = new StubProvider(WebSearchProviderId.EXA, true,
                new WebSearchResponse(WebSearchProviderId.EXA, "live exa"));

        WebSearchResponse response = new WebSearchProviderSelector(properties, List.of(exa), cache)
                .search(request(), context());

        assertNotNull(response);
        assertEquals("live exa", response.content());
        assertEquals(1, exa.calls);
        verify(values).get(anyString());
        verify(redis).delete(anyString());
        verify(values).set(anyString(), anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
    }

    private WebSearchProperties properties(String provider) {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setProvider(provider);
        return properties;
    }

    private WebSearchRequest request() {
        return new WebSearchRequest("test", 5, "fallback", "auto", null);
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        project.setStudentId(7);
        return new AgentContext("session", 7, project, "conversation", 1L, null, List.of(), 0);
    }

    private StringRedisTemplate mockRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        return redis;
    }

    private static class StubProvider implements WebSearchProvider {
        private final WebSearchProviderId id;
        private final boolean enabled;
        private final WebSearchResponse response;
        private final WebSearchException failure;
        private int calls;

        StubProvider(WebSearchProviderId id, boolean enabled, WebSearchResponse response) {
            this.id = id;
            this.enabled = enabled;
            this.response = response;
            this.failure = null;
        }

        StubProvider(WebSearchProviderId id, boolean enabled, WebSearchException failure) {
            this.id = id;
            this.enabled = enabled;
            this.response = null;
            this.failure = failure;
        }

        @Override
        public WebSearchProviderId id() {
            return id;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
