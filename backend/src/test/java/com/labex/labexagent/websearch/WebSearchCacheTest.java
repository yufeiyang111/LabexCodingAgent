package com.labex.labexagent.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WebSearchCacheTest {

    @Test
    void cachesAndRetrievesSearchResults() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheEnabled(true);
        properties.setCacheTtlSeconds(60);

        WebSearchCache cache = new WebSearchCache(properties);
        WebSearchResponse response = new WebSearchResponse(WebSearchProviderId.TAVILY, "1. Result\nurl: https://test.com");

        cache.put("tavily", "spring boot 3", 10, response);

        WebSearchResponse cached = cache.get("tavily", "spring boot 3", 10);
        assertNotNull(cached);
        assertEquals("1. Result\nurl: https://test.com", cached.content());
    }

    @Test
    void returnsNullWhenCacheDisabled() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setCacheEnabled(false);

        WebSearchCache cache = new WebSearchCache(properties);
        WebSearchResponse response = new WebSearchResponse(WebSearchProviderId.TAVILY, "Result");

        cache.put("tavily", "query", 5, response);
        assertNull(cache.get("tavily", "query", 5));
    }
}
