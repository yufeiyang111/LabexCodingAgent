package com.labex.labexagent.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.labex.labexagent.run.AgentRunInteractionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NetworkAccessServiceTest {

    @Test
    void persistsAHashedSingleCommandRequestWithDependencyDomains() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        NetworkAccessService service = new NetworkAccessService(interactions);

        NetworkAccessService.NetworkAccessRequest request = service.begin(
                7, 12, 71L, "conversation-1", "session-1", "run_tests",
                "mvn test", "download dependencies", service.domainsFor("run_tests", "mvn test"));

        ArgumentCaptor<AgentRunInteractionService.WaitingInteraction> captor =
                ArgumentCaptor.forClass(AgentRunInteractionService.WaitingInteraction.class);
        verify(interactions).createWaiting(captor.capture());
        AgentRunInteractionService.WaitingInteraction persisted = captor.getValue();

        assertThat(request.request()).isEqualTo("mvn test");
        assertThat(request.requestDigest()).isEqualTo(service.digest("mvn test"));
        assertThat(persisted.interactionType()).isEqualTo("network");
        assertThat(request.payload()).containsEntry("scope", "single_command");
        assertThat(request.payload()).containsEntry("networkMode", "isolated_bridge");
        assertThat(request.payload()).containsEntry("domains", List.of());
    }

    @Test
    void doesNotUseTechnologyDomainsAsAnApprovalWhitelist() {
        NetworkAccessService service = new NetworkAccessService(mock(AgentRunInteractionService.class));

        assertThat(service.domainsFor("run_tests", "./gradlew test")).isEmpty();
        assertThat(service.domainsFor("shell", "cargo test")).isEmpty();
    }
    @Test
    void recordsGenericRequestKindAndRetryMetadataWithoutTechnologyWhitelist() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        NetworkAccessService service = new NetworkAccessService(interactions);

        NetworkAccessService.NetworkAccessRequest request = service.begin(
                7, 12, 71L, "conversation-1", "session-1", "shell",
                "cargo test", "offline retry", "offline_failure_retry", true, "attempt-1", "tool-call-1", List.of());

        ArgumentCaptor<AgentRunInteractionService.WaitingInteraction> captor =
                ArgumentCaptor.forClass(AgentRunInteractionService.WaitingInteraction.class);
        verify(interactions).createWaiting(captor.capture());
        assertThat(request.payload().get("requestKind")).isEqualTo("offline_failure_retry");
        assertThat(request.payload().get("retryable")).isEqualTo(true);
        assertThat(request.payload().get("toolCallId")).isEqualTo("tool-call-1");
        assertThat(captor.getValue().idempotencyKey()).startsWith("network-access:v2:71:");
    }

    @Test
    void recognizesAnApprovedOfflineRetryForTheExactCommandOnly() {
        AgentRunInteractionService interactions = mock(AgentRunInteractionService.class);
        NetworkAccessService service = new NetworkAccessService(interactions);
        String digest = service.digest("mvn compile");
        when(interactions.hasApprovedNetworkGrant(71L, digest, "offline_failure_retry")).thenReturn(true);

        assertThat(service.hasApprovedOfflineRetryGrant(71L, "mvn compile")).isTrue();
        verify(interactions).hasApprovedNetworkGrant(71L, digest, "offline_failure_retry");
    }

}
