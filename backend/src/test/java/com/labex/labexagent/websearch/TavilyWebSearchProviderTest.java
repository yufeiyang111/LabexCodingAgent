package com.labex.labexagent.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.rag.config.RagConfig;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class TavilyWebSearchProviderTest {

    @Test
    void disabledWhenNoApiKeyConfigured() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setTavilyApiKey(null);
        RagConfig ragConfig = new RagConfig();
        ragConfig.setTavilyApiKey(null);

        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        TavilyWebSearchProvider provider = new TavilyWebSearchProvider(policy, properties, ragConfig);

        assertFalse(provider.isEnabled());
    }

    @Test
    void enabledWhenApiKeyConfigured() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setTavilyApiKey("tvly-testkey123");

        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        TavilyWebSearchProvider provider = new TavilyWebSearchProvider(policy, properties, null);

        assertTrue(provider.isEnabled());
        assertEquals(WebSearchProviderId.TAVILY, provider.id());
    }
}
