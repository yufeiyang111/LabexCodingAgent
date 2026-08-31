package com.labex.labexagent.websearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.rag.config.RagConfig;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TavilyWebSearchProvider implements WebSearchProvider {
    private static final URI TAVILY_ENDPOINT = URI.create("https://api.tavily.com/search");
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final WebSearchProperties properties;
    private final RagConfig ragConfig;
    private final HttpClient httpClient;

    @Autowired
    public TavilyWebSearchProvider(OutboundUrlPolicy outboundUrlPolicy, WebSearchProperties properties,
                                   @Autowired(required = false) RagConfig ragConfig) {
        this(outboundUrlPolicy, properties, ragConfig, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(15, properties.getRequestTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    TavilyWebSearchProvider(OutboundUrlPolicy outboundUrlPolicy, WebSearchProperties properties,
                            RagConfig ragConfig, HttpClient httpClient) {
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.properties = properties;
        this.ragConfig = ragConfig;
        this.httpClient = httpClient;
    }

    @Override
    public WebSearchProviderId id() {
        return WebSearchProviderId.TAVILY;
    }

    @Override
    public boolean isEnabled() {
        if (!properties.isTavilyEnabled()) {
            return false;
        }
        String apiKey = resolveApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new WebSearchException("Tavily API key is not configured", false);
        }

        try {
            OutboundUrlPolicy.ValidatedDestination destination = outboundUrlPolicy.validate(TAVILY_ENDPOINT);
            JsonObject body = new JsonObject();
            body.addProperty("api_key", apiKey);
            body.addProperty("query", request.query());
            body.addProperty("max_results", Math.min(request.numResults(), 20));
            body.addProperty("search_depth", "deep".equalsIgnoreCase(request.type()) ? "advanced" : "basic");
            body.addProperty("include_answer", false);
            body.addProperty("include_raw_content", false);

            HttpRequest httpRequest = HttpRequest.newBuilder(destination.uri())
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int statusCode = response.statusCode();
            String responseText;
            try (InputStream is = response.body()) {
                byte[] bytes = is.readAllBytes();
                responseText = new String(bytes, StandardCharsets.UTF_8);
            }

            if (statusCode == 401 || statusCode == 403) {
                throw new WebSearchException("Tavily API key unauthorized or expired", false);
            }
            if (statusCode == 429 || statusCode >= 500) {
                throw new WebSearchException("Tavily search service temporarily unavailable (HTTP " + statusCode + ")", true);
            }
            if (statusCode != 200) {
                throw new WebSearchException("Tavily search request failed (HTTP " + statusCode + "): " + responseText, false);
            }

            return parseTavilyResponse(responseText);
        } catch (OutboundUrlPolicy.RejectedOutboundUrlException e) {
            throw new WebSearchException("Tavily endpoint blocked by outbound security policy", e, false);
        } catch (WebSearchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("Tavily search interrupted", e, true);
        } catch (Exception e) {
            throw new WebSearchException("Tavily search request failed: " + e.getMessage(), e, true);
        }
    }

    private WebSearchResponse parseTavilyResponse(String json) throws WebSearchException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray resultsArray = root.has("results") && root.get("results").isJsonArray()
                    ? root.getAsJsonArray("results") : new JsonArray();

            if (resultsArray.isEmpty()) {
                return new WebSearchResponse(id(), "No search results found.");
            }

            StringBuilder output = new StringBuilder();
            int index = 1;
            for (JsonElement el : resultsArray) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                String title = item.has("title") && !item.get("title").isJsonNull() ? item.get("title").getAsString().trim() : "";
                String url = item.has("url") && !item.get("url").isJsonNull() ? item.get("url").getAsString().trim() : "";
                String content = item.has("content") && !item.get("content").isJsonNull() ? item.get("content").getAsString().trim() : "";

                if (title.isBlank() && url.isBlank()) continue;

                output.append(index++).append(". ").append(title.isBlank() ? url : title).append("\n");
                output.append("url: ").append(url).append("\n");
                if (!content.isBlank()) {
                    output.append("snippet: ").append(content.replaceAll("\\s+", " ").trim()).append("\n");
                }
                output.append("\n");
            }
            return new WebSearchResponse(id(), output.toString().trim());
        } catch (Exception e) {
            throw new WebSearchException("Failed to parse Tavily search response: " + e.getMessage(), e, false);
        }
    }

    private String resolveApiKey() {
        if (properties.getTavilyApiKey() != null && !properties.getTavilyApiKey().isBlank()) {
            return properties.getTavilyApiKey().trim();
        }
        if (ragConfig != null && ragConfig.getTavilyApiKey() != null && !ragConfig.getTavilyApiKey().isBlank()) {
            return ragConfig.getTavilyApiKey().trim();
        }
        return null;
    }
}
