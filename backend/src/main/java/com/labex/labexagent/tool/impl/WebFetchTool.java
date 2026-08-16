package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.network.WebFetchProperties;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebFetchTool implements AgentTool {
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final WebFetchProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public WebFetchTool(OutboundUrlPolicy outboundUrlPolicy, WebFetchProperties properties) {
        this(outboundUrlPolicy, properties, newHttpClient(properties));
    }

    WebFetchTool(OutboundUrlPolicy outboundUrlPolicy, WebFetchProperties properties, HttpClient httpClient) {
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.properties = properties == null ? new WebFetchProperties() : properties;
        this.httpClient = httpClient;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("web_fetch")
                .description("Fetch content from a webpage or raw-text URL. Use it to read documentation, README files, and raw files.")
                .stringProperty("url", "URL to fetch", true)
                .intProperty("max_chars", "Maximum characters to return; default " + defaultMaxChars(), false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String url = ToolSupport.stringArg(args, "url", "");
        if (url.isBlank()) {
            return ToolResult.failed("url is required");
        }
        int outputMaxChars = boundedOutputChars(args);
        try {
            OutboundUrlPolicy.ValidatedDestination destination = outboundUrlPolicy.validate(url);
            for (int redirectCount = 0; redirectCount <= maxRedirects(); redirectCount++) {
                destination = outboundUrlPolicy.validate(destination.uri());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(destination.uri())
                        .timeout(Duration.ofSeconds(requestTimeoutSeconds()))
                        .header("User-Agent", "LabexAgent/1.0")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (isRedirect(response.statusCode())) {
                        String location = response.headers().firstValue("Location").orElse("");
                        if (location.isBlank()) {
                            return ToolResult.failed("Web fetch redirect did not provide a destination");
                        }
                        destination = outboundUrlPolicy.validateRedirect(destination.uri(), location);
                        continue;
                    }
                    long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    String responseBody = readBoundedResponse(body, contentLength, maxResponseBytes());
                    return ToolResult.ok("status=" + response.statusCode() + "\n"
                            + ToolSupport.limit(stripHtml(responseBody), outputMaxChars));
                }
            }
            return ToolResult.failed("Web fetch exceeded the redirect limit");
        } catch (ResponseTooLargeException e) {
            return ToolResult.failed(e.getMessage());
        } catch (OutboundUrlPolicy.RejectedOutboundUrlException e) {
            return ToolResult.failed("Outbound request blocked: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failed("Web fetch interrupted");
        } catch (Exception e) {
            return ToolResult.failed("Web fetch failed: " + (e.getMessage() == null ? "request error" : e.getMessage()));
        }
    }

    /**
     * 在内容解码前施加字节上限，既覆盖声明长度，也覆盖 chunked 等未知长度响应。
     */
    static String readBoundedResponse(InputStream body, long contentLength, int maxResponseBytes) throws IOException {
        int limit = Math.max(1, maxResponseBytes);
        if (contentLength > limit) {
            throw new ResponseTooLargeException(limit);
        }
        if (body == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(Math.max(contentLength, 0L), limit));
        byte[] buffer = new byte[Math.min(8_192, limit)];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            if (read > limit - total) {
                throw new ResponseTooLargeException(limit);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(int maximumBytes) {
            super("Web fetch response exceeds the configured maximum size of " + maximumBytes + " bytes");
        }
    }

    private static HttpClient newHttpClient(WebFetchProperties properties) {
        WebFetchProperties safeProperties = properties == null ? new WebFetchProperties() : properties;
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, safeProperties.getConnectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private int boundedOutputChars(JsonObject args) {
        int min = Math.max(1, properties.getMinMaxChars());
        int max = Math.max(min, properties.getMaxMaxChars());
        int defaultValue = Math.min(max, Math.max(min, properties.getDefaultMaxChars()));
        return Math.min(max, Math.max(min, ToolSupport.intArg(args, "max_chars", defaultValue)));
    }

    private int requestTimeoutSeconds() {
        return Math.max(1, properties.getRequestTimeoutSeconds());
    }

    private int maxRedirects() {
        return Math.max(0, properties.getMaxRedirects());
    }

    private int maxResponseBytes() {
        return Math.max(1, properties.getMaxResponseBytes());
    }

    private int defaultMaxChars() {
        int min = Math.max(1, properties.getMinMaxChars());
        int max = Math.max(min, properties.getMaxMaxChars());
        return Math.min(max, Math.max(min, properties.getDefaultMaxChars()));
    }

    private boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    private String stripHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?is)<script.*?</script>", "")
                .replaceAll("(?is)<style.*?</style>", "")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}