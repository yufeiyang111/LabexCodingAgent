package com.labex.labexagent.websearch;

import com.google.gson.JsonObject;
import com.labex.labexagent.network.OutboundUrlPolicy;
import java.io.IOException;
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
public class McpWebSearchClient {
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final McpWebSearchResponseParser responseParser;

    @Autowired
    public McpWebSearchClient(OutboundUrlPolicy outboundUrlPolicy, WebSearchProperties properties) {
        this(outboundUrlPolicy, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), new McpWebSearchResponseParser());
    }

    McpWebSearchClient(OutboundUrlPolicy outboundUrlPolicy, WebSearchProperties properties,
                       HttpClient httpClient, McpWebSearchResponseParser responseParser) {
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.properties = properties;
        this.httpClient = httpClient;
        this.responseParser = responseParser;
    }

    public String call(URI endpoint, String toolName, JsonObject arguments) throws WebSearchException {
        return call(endpoint, toolName, arguments, "", "");
    }

    public String call(URI endpoint, String toolName, JsonObject arguments, String headerName, String headerValue)
            throws WebSearchException {
        try {
            URI destination = outboundUrlPolicy.validate(endpoint).uri();
            JsonObject params = new JsonObject();
            params.addProperty("name", toolName);
            params.add("arguments", arguments);
            JsonObject payload = new JsonObject();
            payload.addProperty("jsonrpc", "2.0");
            payload.addProperty("id", 1);
            payload.addProperty("method", "tools/call");
            payload.add("params", params);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(destination)
                    .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LabexAgent/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
            if (headerName != null && !headerName.isBlank() && headerValue != null && !headerValue.isBlank()) {
                requestBuilder.header(headerName, headerValue);
            }
            HttpRequest request = requestBuilder.build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String body = readBounded(response);
            if (status < 200 || status >= 300) {
                throw new WebSearchException("Web-search provider request failed (HTTP " + status + ")",
                        status == 408 || status == 429 || status >= 500);
            }
            return responseParser.extractContent(body);
        } catch (OutboundUrlPolicy.RejectedOutboundUrlException e) {
            throw new WebSearchException("Web-search provider URL was blocked", e, false);
        } catch (WebSearchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("Web-search provider request was interrupted", e, true);
        } catch (Exception e) {
            throw new WebSearchException("Web-search provider request failed", e, true);
        }
    }

    private String readBounded(HttpResponse<InputStream> response) throws IOException, WebSearchException {
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        int maxBytes = Math.max(1_024, properties.getMaxResponseBytes());
        if (contentLength > maxBytes) {
            throw new WebSearchException("Web-search provider response exceeded the configured limit", true);
        }
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new WebSearchException("Web-search provider response exceeded the configured limit", true);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
