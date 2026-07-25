package com.labex.labexagent.websearch;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class ExaMcpWebSearchProvider implements WebSearchProvider {
    private static final URI EXA_ENDPOINT = URI.create("https://mcp.exa.ai/mcp");
    private final WebSearchProperties properties;
    private final McpWebSearchClient client;

    public ExaMcpWebSearchProvider(WebSearchProperties properties, McpWebSearchClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public WebSearchProviderId id() {
        return WebSearchProviderId.EXA;
    }

    @Override
    public boolean isEnabled() {
        return properties.isExaEnabled();
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
        if (!isEnabled()) {
            throw new WebSearchException("Exa web search is disabled", false);
        }
        JsonObject arguments = new JsonObject();
        arguments.addProperty("query", request.query());
        arguments.addProperty("numResults", request.numResults());
        arguments.addProperty("livecrawl", request.livecrawl());
        arguments.addProperty("type", request.type());
        if (request.contextMaxCharacters() != null) {
            arguments.addProperty("contextMaxCharacters", request.contextMaxCharacters());
        }
        return new WebSearchResponse(id(), client.call(endpoint(), "web_search_exa", arguments));
    }

    private URI endpoint() {
        String apiKey = properties.getExaApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return EXA_ENDPOINT;
        }
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        return URI.create(EXA_ENDPOINT + "?exaApiKey=" + encodedKey);
    }
}
