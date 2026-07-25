package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.websearch.WebSearchException;
import com.labex.labexagent.websearch.WebSearchProviderSelector;
import com.labex.labexagent.websearch.WebSearchRequest;
import com.labex.labexagent.websearch.WebSearchResponse;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool implements AgentTool {
    private static final int DEFAULT_RESULTS = 10;
    private static final int MAX_RESULTS = 30;
    private static final int MAX_CONTEXT_CHARACTERS = 1_000_000;
    private static final Set<String> LIVECRAWL_VALUES = Set.of("fallback", "preferred");
    private static final Set<String> TYPE_VALUES = Set.of("auto", "fast", "deep");
    private final WebSearchProviderSelector providerSelector;

    public WebSearchTool(WebSearchProviderSelector providerSelector) {
        this.providerSelector = providerSelector;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("web_search")
                .description("Search the web for current external information and source URLs. Results are discovery evidence only; use web_fetch to read a selected URL.")
                .stringProperty("query", "Search query", true)
                .intProperty("numResults", "Maximum results to return, default 10, maximum 30", false)
                .stringProperty("livecrawl", "Exa crawl preference: fallback or preferred", false)
                .stringProperty("type", "Exa search type: auto, fast, or deep", false)
                .intProperty("contextMaxCharacters", "Maximum Exa context characters", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String query = ToolSupport.stringArgMulti(args, "", "query", "q", "search").trim();
        if (query.isBlank()) {
            return ToolResult.failed("query is required");
        }
        try {
            WebSearchRequest request = new WebSearchRequest(
                    query,
                    boundedResults(args),
                    enumArg(args, "livecrawl", "fallback", LIVECRAWL_VALUES),
                    enumArg(args, "type", "auto", TYPE_VALUES),
                    optionalContextLimit(args));
            WebSearchResponse response = providerSelector.search(request, context);
            return ToolResult.ok("provider: " + response.provider().name().toLowerCase(Locale.ROOT)
                    + "\n" + response.content());
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(e.getMessage());
        } catch (WebSearchException e) {
            return ToolResult.failed("Web search failed: " + e.getMessage());
        }
    }

    private int boundedResults(JsonObject args) {
        int value = ToolSupport.intArg(args, "numResults", ToolSupport.intArg(args, "max_results", DEFAULT_RESULTS));
        if (value < 1 || value > MAX_RESULTS) {
            throw new IllegalArgumentException("numResults must be between 1 and " + MAX_RESULTS);
        }
        return value;
    }

    private Integer optionalContextLimit(JsonObject args) {
        if (args == null || !args.has("contextMaxCharacters") || args.get("contextMaxCharacters").isJsonNull()) {
            return null;
        }
        int value = ToolSupport.intArg(args, "contextMaxCharacters", -1);
        if (value < 1 || value > MAX_CONTEXT_CHARACTERS) {
            throw new IllegalArgumentException("contextMaxCharacters must be between 1 and " + MAX_CONTEXT_CHARACTERS);
        }
        return value;
    }

    private String enumArg(JsonObject args, String name, String defaultValue, Set<String> supportedValues) {
        String value = ToolSupport.stringArg(args, name, defaultValue).trim().toLowerCase(Locale.ROOT);
        if (!supportedValues.contains(value)) {
            throw new IllegalArgumentException(name + " must be one of " + String.join(", ", supportedValues));
        }
        return value;
    }
}
