package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.rag.service.WebSearchService;
import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool implements AgentTool {
    private final WebSearchService webSearchService;

    public WebSearchTool(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("web_search")
                .description("Search the web and read page excerpts for current or external information. Use exact names, versions, and dates in the query. Treat fetched page bodies as evidence; snippets are discovery clues.")
                .stringProperty("query", "Search query", true)
                .intProperty("max_results", "Maximum results to return, default 10", false)
                .intProperty("fetch_pages", "How many result pages to read, default 5", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String query = ToolSupport.stringArgMulti(args, "", "query", "q", "search");
        if (query.isBlank()) {
            return ToolResult.failed("query is required");
        }
        int maxResults = Math.min(30, Math.max(3, ToolSupport.intArg(args, "max_results", 10)));
        int fetchPages = Math.min(maxResults, Math.max(0, ToolSupport.intArg(args, "fetch_pages", Math.min(6, maxResults))));
        WebSearchService.SearchBundle bundle = webSearchService.search(query, maxResults, fetchPages);
        StringBuilder out = new StringBuilder();
        out.append("Web search query: ").append(query).append('\n');
        if (!bundle.getKeywords().isEmpty()) {
            out.append("Display keywords: ").append(String.join(", ", bundle.getKeywords())).append('\n');
        }
        if (!bundle.getExactPhrases().isEmpty()) {
            out.append("Exact phrases that must be preserved: ").append(String.join(", ", bundle.getExactPhrases())).append('\n');
        }
        out.append("Important: only entries with page_body_fetched=true are verified web evidence. exact_entity_match=false means the result may discuss a nearby but different version/name.\n\n");

        int index = 1;
        for (WebSearchService.WebSearchResult result : bundle.getResults()) {
            String content = result.getContent() == null ? "" : result.getContent();
            out.append(index++).append(". ").append(result.getTitle()).append('\n');
            out.append("url: ").append(result.getUrl()).append('\n');
            out.append("host: ").append(host(result.getUrl())).append('\n');
            out.append("engine: ").append(result.getEngine()).append('\n');
            out.append("page_body_fetched: ").append(!content.isBlank()).append('\n');
            out.append("exact_entity_match: ").append(result.isExactMatch()).append('\n');
            out.append("evidence_level: ").append(evidenceLevel(result, !content.isBlank())).append('\n');
            out.append("source_quality: ").append(sourceQuality(host(result.getUrl()), !content.isBlank())).append('\n');
            if (result.getPublishedAt() != null && !result.getPublishedAt().isBlank()) {
                out.append("published_or_updated: ").append(result.getPublishedAt()).append('\n');
            }
            if (result.getSnippet() != null && !result.getSnippet().isBlank()) {
                out.append("snippet: ").append(ToolSupport.limit(result.getSnippet(), 700)).append('\n');
            }
            if (!content.isBlank()) {
                out.append("page_excerpt: ").append(ToolSupport.limit(content, 2200)).append('\n');
            }
            out.append('\n');
        }
        return ToolResult.ok(out.toString());
    }

    private String host(String url) {
        try {
            String host = URI.create(url == null ? "" : url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private String evidenceLevel(WebSearchService.WebSearchResult result, boolean contentFetched) {
        if (result.isFallback()) {
            return "fallback_search_page";
        }
        if (contentFetched) {
            return result.isExactMatch() ? "verified_exact_page_body" : "verified_page_body";
        }
        return result.isExactMatch() ? "search_snippet_exact" : "search_snippet_only";
    }

    private String sourceQuality(String host, boolean contentFetched) {
        if (host == null || host.isBlank()) {
            return contentFetched ? "body_fetched" : "snippet_only";
        }
        boolean primaryLike = host.endsWith(".gov")
                || host.endsWith(".edu")
                || host.contains("docs.")
                || host.contains("developer.")
                || host.contains("learn.microsoft.com")
                || host.contains("openai.com")
                || host.contains("anthropic.com")
                || host.contains("cloud.google.com")
                || host.contains("github.com");
        if (primaryLike && contentFetched) {
            return "primary_or_docs_verified";
        }
        if (primaryLike) {
            return "primary_or_docs_snippet";
        }
        return contentFetched ? "secondary_verified" : "secondary_snippet";
    }
}
