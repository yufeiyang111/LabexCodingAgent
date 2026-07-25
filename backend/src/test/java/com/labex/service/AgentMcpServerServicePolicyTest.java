package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.secret.SecretStore;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class AgentMcpServerServicePolicyTest {

    @Test
    void rejectsMcpEndpointsThatResolveToPrivateAddresses() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("10.0.0.9")});
        AgentMcpServerService service = new AgentMcpServerService(policy, mock(SecretStore.class));

        assertThrows(OutboundUrlPolicy.RejectedOutboundUrlException.class,
                () -> service.validateEndpoint("https://mcp.example.test/rpc"));
    }
}
