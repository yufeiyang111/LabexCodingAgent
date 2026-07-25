package com.labex.labexagent.websearch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.labex.labexagent.network.OutboundUrlPolicy;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class PublicWebSearchFallbackProviderPolicyTest {

    @Test
    void rejectsBlockedSearchEndpointsBeforeSendingARequest() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        WebSearchProperties properties = new WebSearchProperties();
        PublicWebSearchFallbackProvider provider = new PublicWebSearchFallbackProvider(policy, properties);

        WebSearchException error = assertThrows(WebSearchException.class,
                () -> provider.search(new WebSearchRequest("security test", 3, "fallback", "auto", null), null));

        assertFalse(error.isRecoverable());
    }
}
