package com.labex.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.labex.entity.AgentMcpServer;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.secret.LocalEnvelopeSecretStore;
import com.labex.labexagent.secret.SecretStore;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentMcpServerSecretTest {

    @Test
    void storesMcpAuthorizationHeadersAsEncryptedSecrets() throws Exception {
        SecretStore secrets = new LocalEnvelopeSecretStore(masterKey(), false);
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        AgentMcpServerService service = spy(new AgentMcpServerService(policy, secrets));
        doAnswer(invocation -> true).when(service).save(any(AgentMcpServer.class));

        AgentMcpServer server = service.create(7, Map.of(
                "serverKey", "docs",
                "serverName", "Docs",
                "transport", "http",
                "endpoint", "https://mcp.example.test/rpc",
                "authHeader", "Bearer mcp-secret"));

        assertEquals("", server.getAuthHeader());
        assertNotNull(server.getAuthHeaderEncrypted());
        assertFalse(server.getAuthHeaderEncrypted().contains("mcp-secret"));
        assertEquals("Bearer mcp-secret", service.resolveAuthHeader(server));
    }

    private String masterKey() {
        return Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
    }
}
