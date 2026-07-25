package com.labex.labexagent.websearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class ParallelMcpWebSearchProvider implements WebSearchProvider {
    private static final URI PARALLEL_ENDPOINT = URI.create("https://search.parallel.ai/mcp");
    private final WebSearchProperties properties;
    private final McpWebSearchClient client;

    public ParallelMcpWebSearchProvider(WebSearchProperties properties, McpWebSearchClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public WebSearchProviderId id() {
        return WebSearchProviderId.PARALLEL;
    }

    @Override
    public boolean isEnabled() {
        return properties.isParallelEnabled();
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
        if (!isEnabled()) {
            throw new WebSearchException("Parallel web search is disabled", false);
        }
        JsonObject arguments = new JsonObject();
        arguments.addProperty("objective", request.query());
        JsonArray queries = new JsonArray();
        queries.add(request.query());
        arguments.add("search_queries", queries);
        String sessionId = sessionId(context);
        if (!sessionId.isBlank()) {
            arguments.addProperty("session_id", sessionId);
        }
        return new WebSearchResponse(id(), client.call(PARALLEL_ENDPOINT, "web_search", arguments,
                "x-api-key", properties.getParallelApiKey()));
    }

    private String sessionId(AgentContext context) {
        if (context == null) {
            return "";
        }
        if (context.getSessionId() != null && !context.getSessionId().isBlank()) {
            return context.getSessionId();
        }
        if (context.getConversationId() != null && !context.getConversationId().isBlank()) {
            return context.getConversationId();
        }
        return context.getTaskId() == null ? "" : String.valueOf(context.getTaskId());
    }
}
