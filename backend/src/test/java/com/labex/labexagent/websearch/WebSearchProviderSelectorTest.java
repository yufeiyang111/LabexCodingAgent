package com.labex.labexagent.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.runtime.AgentContext;
import java.util.List;
import org.junit.jupiter.api.Test;

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
                .search(request(), null);

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
                .search(request(), null);

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
                () -> new WebSearchProviderSelector(properties, List.of(exa, fallback)).search(request(), null));

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
                .search(request(), null);

        assertEquals(WebSearchProviderId.PARALLEL, response.provider());
        assertEquals(0, exa.calls);
        assertEquals(1, parallel.calls);
        assertEquals(0, fallback.calls);
    }

    private WebSearchProperties properties(String provider) {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setProvider(provider);
        return properties;
    }

    private WebSearchRequest request() {
        return new WebSearchRequest("test", 5, "fallback", "auto", null);
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
