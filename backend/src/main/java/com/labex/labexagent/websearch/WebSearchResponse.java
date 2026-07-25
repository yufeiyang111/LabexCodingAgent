package com.labex.labexagent.websearch;

public record WebSearchResponse(WebSearchProviderId provider, String content) {
    public WebSearchResponse {
        content = content == null ? "" : content.trim();
    }
}
