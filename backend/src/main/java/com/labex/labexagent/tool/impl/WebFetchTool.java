package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebFetchTool
implements AgentTool {
    private static final int MAX_REDIRECTS = 5;
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final HttpClient httpClient;

    @Autowired
    public WebFetchTool(OutboundUrlPolicy outboundUrlPolicy) {
        this(outboundUrlPolicy, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20L))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    WebFetchTool(OutboundUrlPolicy outboundUrlPolicy, HttpClient httpClient) {
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.httpClient = httpClient;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("web_fetch").description("\u6293\u53d6\u4e00\u4e2a\u7f51\u9875\u6216\u539f\u59cb\u6587\u672c URL \u7684\u5185\u5bb9\u3002\u7528\u4e8e\u8bfb\u53d6\u6587\u6863\u3001README\u3001raw \u6587\u4ef6\u3002").stringProperty("url", "URL to fetch", true).intProperty("max_chars", "\u6700\u5927\u8fd4\u56de\u5b57\u7b26\u6570\uff0c\u9ed8\u8ba412000", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String url = ToolSupport.stringArg((JsonObject)args, "url", "");
        if (url.isBlank()) {
            return ToolResult.failed("url is required");
        }
        int max = Math.min(50000, Math.max(1000, ToolSupport.intArg((JsonObject)args, "max_chars", 12000)));
        try {
            OutboundUrlPolicy.ValidatedDestination destination = outboundUrlPolicy.validate(url);
            for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
                destination = outboundUrlPolicy.validate(destination.uri());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(destination.uri())
                        .timeout(Duration.ofSeconds(60L))
                        .header("User-Agent", "LabexAgent/1.0")
                        .GET()
                        .build();
                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (!isRedirect(response.statusCode())) {
                    return ToolResult.ok("status=" + response.statusCode() + "\n"
                            + ToolSupport.limit(this.stripHtml(response.body()), max));
                }
                String location = response.headers().firstValue("Location").orElse("");
                if (location.isBlank()) {
                    return ToolResult.failed("Web fetch redirect did not provide a destination");
                }
                destination = outboundUrlPolicy.validateRedirect(destination.uri(), location);
            }
            return ToolResult.failed("Web fetch exceeded the redirect limit");
        } catch (OutboundUrlPolicy.RejectedOutboundUrlException e) {
            return ToolResult.failed("Outbound request blocked: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.failed("Web fetch failed: " + (e.getMessage() == null ? "request error" : e.getMessage()));
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    private String stripHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?is)<script.*?</script>", "").replaceAll("(?is)<style.*?</style>", "").replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
