package com.labex.labexagent.websearch;

public record WebSearchRequest(String query, int numResults, String livecrawl, String type,
                               Integer contextMaxCharacters) {
    public WebSearchRequest {
        query = query == null ? "" : query.trim();
        livecrawl = livecrawl == null || livecrawl.isBlank() ? "fallback" : livecrawl;
        type = type == null || type.isBlank() ? "auto" : type;
    }
}
