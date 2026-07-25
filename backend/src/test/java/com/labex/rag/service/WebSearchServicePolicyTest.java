package com.labex.rag.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.labex.labexagent.network.OutboundUrlPolicy;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class WebSearchServicePolicyTest {

    @Test
    void rejectsBlockedResultPagesBeforeJsoupOpensTheConnection() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("169.254.169.254")});
        WebSearchService service = new WebSearchService(policy);

        assertThrows(OutboundUrlPolicy.RejectedOutboundUrlException.class,
                () -> service.fetchDocument("http://result.example.test/page", 1000, 1024, false));
    }
}
