package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.websearch.WebSearchException;
import com.labex.labexagent.websearch.WebSearchProperties;
import com.labex.labexagent.websearch.WebSearchProvider;
import com.labex.labexagent.websearch.WebSearchProviderId;
import com.labex.labexagent.websearch.WebSearchProviderSelector;
import com.labex.labexagent.websearch.WebSearchRequest;
import com.labex.labexagent.websearch.WebSearchResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSearchToolTest {

    @Test
    void preservesLegacyMaxResultsWhileUsingDiscoveryOnlyOutput() {
        WebSearchTool tool = new WebSearchTool(selector());
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", "Spring Boot 3.2 release notes");
        arguments.addProperty("max_results", 4);

        com.labex.labexagent.tool.ToolResult result = tool.execute(null, arguments);

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("provider: exa"));
        assertTrue(result.getContent().contains("url: https://docs.example.test/release"));
        assertFalse(result.getContent().contains("page_body_fetched"));
    }

    @Test
    void rejectsOutOfRangeResultCountsBeforeCallingAProvider() {
        WebSearchTool tool = new WebSearchTool(selector());
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", "test");
        arguments.addProperty("numResults", 31);

        com.labex.labexagent.tool.ToolResult result = tool.execute(null, arguments);

        assertFalse(result.isSuccess());
        assertEquals("numResults must be between 1 and 30", result.getContent());
    }

    private WebSearchProviderSelector selector() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setProvider("exa");
        return new WebSearchProviderSelector(properties, List.of(new StaticProvider()));
    }

    private static class StaticProvider implements WebSearchProvider {
        @Override
        public WebSearchProviderId id() {
            return WebSearchProviderId.EXA;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
            return new WebSearchResponse(WebSearchProviderId.EXA,
                    "1. Release notes\nurl: https://docs.example.test/release\nsnippet: discovery result");
        }
    }
}
