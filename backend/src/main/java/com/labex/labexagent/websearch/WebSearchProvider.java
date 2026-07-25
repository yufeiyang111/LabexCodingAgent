package com.labex.labexagent.websearch;

import com.labex.labexagent.runtime.AgentContext;

public interface WebSearchProvider {
    WebSearchProviderId id();

    boolean isEnabled();

    WebSearchResponse search(WebSearchRequest request, AgentContext context) throws WebSearchException;
}
