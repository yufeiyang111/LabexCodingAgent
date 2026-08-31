package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.network.HtmlToMarkdownConverter;
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
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebFetchTool implements AgentTool {
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Set<String> SUPPORTED_FORMATS = Set.of("markdown", "text", "html");
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
                .description("Fetch content from a webpage, raw-text, or image URL. Returns formatted Markdown by default, preserving titles, code blocks, lists, and tables.")
                .stringProperty("url", "The URL to fetch content from", true)
                .stringProperty("format", "The format to return the content in (markdown, text, or html). Defaults to markdown.", false)
                .intProperty("timeout", "Optional request timeout in seconds (max 120)", false)
                .intProperty("max_chars", "Maximum characters to return; default " + defaultMaxChars(), false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String url = ToolSupport.stringArg(args, "url", "").trim();
        if (url.isBlank()) {
            return ToolResult.failed("url is required");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failed("url must start with http:// or https://");
        }

        String format = parseFormat(args);
        int timeoutSeconds = parseTimeout(args);
        int outputMaxChars = boundedOutputChars(args);

        try {
            OutboundUrlPolicy.ValidatedDestination destination = outboundUrlPolicy.validate(url);
            for (int redirectCount = 0; redirectCount <= maxRedirects(); redirectCount++) {
                destination = outboundUrlPolicy.validate(destination.uri());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(destination.uri())
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("Accept", acceptHeaderFor(format))
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
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

                    String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    byte[] rawBytes = readBoundedBytes(body, contentLength, maxResponseBytes());

                    // 检查是否为图片类型
                    if (isImageMime(contentType)) {
                        return ToolResult.ok("status=" + response.statusCode() + " content_type=" + contentType
                                + "\n[Image URL: " + destination.uri() + ", size=" + rawBytes.length + " bytes]");
                    }

                    String responseBody = new String(rawBytes, StandardCharsets.UTF_8);
                    String processedContent = formatContent(responseBody, contentType, format);

                    return ToolResult.ok("status=" + response.statusCode() + " content_type=" + (contentType.isBlank() ? "unknown" : contentType)
                            + "\n\n" + ToolSupport.limit(processedContent, outputMaxChars));
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

    private String formatContent(String body, String contentType, String format) {
        boolean isHtml = contentType.contains("text/html") || (body != null && body.trim().startsWith("<") && body.contains("</html>"));
        if (!isHtml) {
            return body == null ? "" : body.trim();
        }

        return switch (format) {
            case "markdown" -> HtmlToMarkdownConverter.convert(body);
            case "text" -> Jsoup.parse(body).text();
            case "html" -> body;
            default -> HtmlToMarkdownConverter.convert(body);
        };
    }

    private static String acceptHeaderFor(String format) {
        return switch (format) {
            case "markdown" -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
            case "text" -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1";
            case "html" -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, */*;q=0.1";
            default -> "text/markdown;q=1.0, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1";
        };
    }

    private boolean isImageMime(String contentType) {
        return contentType.startsWith("image/") || contentType.contains("image/png")
                || contentType.contains("image/jpeg") || contentType.contains("image/webp")
                || contentType.contains("image/gif") || contentType.contains("image/svg+xml");
    }

    private String parseFormat(JsonObject args) {
        String format = ToolSupport.stringArg(args, "format", "markdown").trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_FORMATS.contains(format) ? format : "markdown";
    }

    private int parseTimeout(JsonObject args) {
        int timeout = ToolSupport.intArg(args, "timeout", requestTimeoutSeconds());
        return Math.max(1, Math.min(120, timeout));
    }

    static byte[] readBoundedBytes(InputStream body, long contentLength, int maxResponseBytes) throws IOException {
        int limit = Math.max(1, maxResponseBytes);
        if (contentLength > limit) {
            throw new ResponseTooLargeException(limit);
        }
        if (body == null) {
            return new byte[0];
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
        return output.toByteArray();
    }

    /**
     * 兼容旧方法签名，内部委托给 readBoundedBytes
     */
    static String readBoundedResponse(InputStream body, long contentLength, int maxResponseBytes) throws IOException {
        byte[] bytes = readBoundedBytes(body, contentLength, maxResponseBytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
}
