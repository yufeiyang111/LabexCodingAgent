package com.labex.labexagent.websearch;

import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.runtime.AgentContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PublicWebSearchFallbackProvider implements WebSearchProvider {
    private static final int MAX_REDIRECTS = 3;
    private static final URI DUCKDUCKGO_SEARCH = URI.create("https://html.duckduckgo.com/html/");
    private static final URI BING_SEARCH = URI.create("https://www.bing.com/search");
    private final OutboundUrlPolicy outboundUrlPolicy;
    private final WebSearchProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public PublicWebSearchFallbackProvider(OutboundUrlPolicy outboundUrlPolicy, WebSearchProperties properties) {
        this(outboundUrlPolicy, properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    PublicWebSearchFallbackProvider(OutboundUrlPolicy outboundUrlPolicy, WebSearchProperties properties,
                                    HttpClient httpClient) {
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public WebSearchProviderId id() {
        return WebSearchProviderId.PUBLIC_FALLBACK;
    }

    @Override
    public boolean isEnabled() {
        return properties.isPublicFallbackEnabled();
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException {
        if (!isEnabled()) {
            throw new WebSearchException("Public web-search fallback is disabled", false);
        }
        Map<String, SearchResult> results = new LinkedHashMap<>();
        WebSearchException recoverableFailure = collectDuckDuckGoResults(results, request);
        if (results.size() < request.numResults()) {
            recoverableFailure = collectBingResults(results, request, recoverableFailure);
        }
        if (results.isEmpty()) {
            if (recoverableFailure != null) {
                throw recoverableFailure;
            }
            throw new WebSearchException("Public web-search fallback returned no results", true);
        }
        StringBuilder output = new StringBuilder();
        int index = 1;
        for (SearchResult result : results.values()) {
            output.append(index++).append(". ").append(result.title()).append('\n');
            output.append("url: ").append(result.url()).append('\n');
            if (!result.snippet().isBlank()) {
                output.append("snippet: ").append(result.snippet()).append('\n');
            }
            output.append('\n');
        }
        return new WebSearchResponse(id(), output.toString());
    }

    private WebSearchException collectDuckDuckGoResults(Map<String, SearchResult> results, WebSearchRequest request)
            throws WebSearchException {
        try {
            collect(results, parseDuckDuckGo(fetch(searchUri(DUCKDUCKGO_SEARCH, request.query()))), request.numResults());
            return null;
        } catch (WebSearchException e) {
            if (!e.isRecoverable()) {
                throw e;
            }
            return e;
        }
    }

    private WebSearchException collectBingResults(Map<String, SearchResult> results, WebSearchRequest request,
                                                   WebSearchException previousFailure) throws WebSearchException {
        try {
            collect(results, parseBing(fetch(searchUri(BING_SEARCH, request.query()))), request.numResults());
            return previousFailure;
        } catch (WebSearchException e) {
            if (!e.isRecoverable()) {
                throw e;
            }
            return previousFailure == null ? e : previousFailure;
        }
    }

    private URI searchUri(URI base, String query) {
        String separator = base.getQuery() == null ? "?" : "&";
        return URI.create(base + separator + "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
    }

    private String fetch(URI uri) throws WebSearchException {
        try {
            OutboundUrlPolicy.ValidatedDestination destination = outboundUrlPolicy.validate(uri);
            for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
                HttpRequest request = HttpRequest.newBuilder(destination.uri())
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("User-Agent", "Mozilla/5.0 (compatible; LabexAgent/1.0)")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location").orElse("");
                    response.body().close();
                    if (location.isBlank()) {
                        throw new WebSearchException("Public web-search fallback redirect had no destination", true);
                    }
                    destination = outboundUrlPolicy.validateRedirect(destination.uri(), location);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    response.body().close();
                    throw new WebSearchException("Public web-search fallback request failed (HTTP "
                            + response.statusCode() + ")", response.statusCode() == 408 || response.statusCode() == 429
                            || response.statusCode() >= 500);
                }
                return readBounded(response);
            }
            throw new WebSearchException("Public web-search fallback exceeded the redirect limit", true);
        } catch (OutboundUrlPolicy.RejectedOutboundUrlException e) {
            throw new WebSearchException("Public web-search fallback URL was blocked", e, false);
        } catch (WebSearchException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("Public web-search fallback request was interrupted", e, true);
        } catch (Exception e) {
            throw new WebSearchException("Public web-search fallback request failed", e, true);
        }
    }

    private String readBounded(HttpResponse<InputStream> response) throws IOException, WebSearchException {
        int maxBytes = Math.max(1_024, properties.getMaxResponseBytes());
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > maxBytes) {
            throw new WebSearchException("Public web-search fallback response exceeded the configured limit", true);
        }
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new WebSearchException("Public web-search fallback response exceeded the configured limit", true);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private Map<String, SearchResult> parseDuckDuckGo(String body) {
        Document document = Jsoup.parse(body);
        Map<String, SearchResult> results = new LinkedHashMap<>();
        for (Element result : document.select(".result")) {
            Element link = result.selectFirst("a.result__a[href]");
            if (link == null) {
                continue;
            }
            addResult(results, link.text(), link.attr("href"), text(result.selectFirst(".result__snippet")));
        }
        return results;
    }

    private Map<String, SearchResult> parseBing(String body) {
        Document document = Jsoup.parse(body);
        Map<String, SearchResult> results = new LinkedHashMap<>();
        for (Element result : document.select("li.b_algo")) {
            Element link = result.selectFirst("h2 a[href]");
            if (link == null) {
                continue;
            }
            addResult(results, link.text(), link.attr("href"), text(result.selectFirst(".b_caption p")));
        }
        return results;
    }

    private void collect(Map<String, SearchResult> target, Map<String, SearchResult> candidates, int limit) {
        for (SearchResult candidate : candidates.values()) {
            if (target.size() >= limit) {
                return;
            }
            target.putIfAbsent(candidate.url(), candidate);
        }
    }

    private void addResult(Map<String, SearchResult> results, String title, String url, String snippet) {
        String cleanTitle = clean(title);
        String cleanUrl = clean(url);
        if (cleanTitle.isBlank() || cleanUrl.isBlank() || !cleanUrl.startsWith("http")) {
            return;
        }
        results.putIfAbsent(cleanUrl, new SearchResult(cleanTitle, cleanUrl, clean(snippet)));
    }

    private String text(Element element) {
        return element == null ? "" : element.text();
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private record SearchResult(String title, String url, String snippet) {
    }
}
