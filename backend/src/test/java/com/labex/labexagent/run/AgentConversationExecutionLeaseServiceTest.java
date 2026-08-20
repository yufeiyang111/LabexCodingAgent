package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.mapper.AgentConversationMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationExecutionLeaseServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 17, 19, 15);
    private static final LocalDateTime EXPIRES_AT = NOW.plusMinutes(2);

    @Test
    void claimsAnIdleConversationByAdvancingItsDurableEpoch() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversation conversation = conversation(null, 0L, null);
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        when(conversations.updateById(any(AgentConversation.class))).thenReturn(1);
        AgentConversationExecutionLeaseService service = new AgentConversationExecutionLeaseService(conversations);

        AgentConversationExecutionLeaseService.Claim claim = service.claim(
                new AgentConversationExecutionLeaseService.ClaimRequest(
                        17, 23, "conv-a", "runner-a", NOW, EXPIRES_AT));

        assertThat(claim.claimed()).isTrue();
        assertThat(claim.executionEpoch()).isEqualTo(1L);
        assertThat(claim.leaseExpiresAt()).isEqualTo(EXPIRES_AT);

        ArgumentCaptor<AgentConversation> captor = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversations).updateById(captor.capture());
        assertThat(captor.getValue().getExecutionOwner()).isEqualTo("runner-a");
        assertThat(captor.getValue().getExecutionEpoch()).isEqualTo(1L);
        assertThat(captor.getValue().getExecutionLeaseExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(captor.getValue().getExecutionHeartbeatAt()).isEqualTo(NOW);
    }

    @Test
    void doesNotStealAnUnexpiredLeaseOwnedByAnotherRunner() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversation conversation = conversation("runner-a", 4L, NOW.plusMinutes(1));
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        AgentConversationExecutionLeaseService service = new AgentConversationExecutionLeaseService(conversations);

        AgentConversationExecutionLeaseService.Claim claim = service.claim(
                new AgentConversationExecutionLeaseService.ClaimRequest(
                        17, 23, "conv-a", "runner-b", NOW, EXPIRES_AT));

        assertThat(claim.claimed()).isFalse();
        assertThat(claim.executionEpoch()).isEqualTo(4L);
        assertThat(claim.owner()).isEqualTo("runner-a");
        verify(conversations, never()).updateById(any(AgentConversation.class));
    }

    @Test
    void expiredLeaseCanBeTakenOverButStaleOwnerCannotHeartbeatOrReleaseIt() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversation conversation = conversation("runner-a", 4L, NOW.minusSeconds(1));
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        when(conversations.updateById(any(AgentConversation.class))).thenReturn(1);
        AgentConversationExecutionLeaseService service = new AgentConversationExecutionLeaseService(conversations);

        AgentConversationExecutionLeaseService.Claim takeover = service.claim(
                new AgentConversationExecutionLeaseService.ClaimRequest(
                        17, 23, "conv-a", "runner-b", NOW, EXPIRES_AT));

        assertThat(takeover.claimed()).isTrue();
        assertThat(takeover.executionEpoch()).isEqualTo(5L);
        assertThat(service.heartbeat(new AgentConversationExecutionLeaseService.LeaseRef(
                17, 23, "conv-a", "runner-a", 4L, NOW, EXPIRES_AT.plusMinutes(1)))).isFalse();
        assertThat(service.release(new AgentConversationExecutionLeaseService.LeaseRef(
                17, 23, "conv-a", "runner-a", 4L, NOW, EXPIRES_AT))).isFalse();
        assertThat(conversation.getExecutionOwner()).isEqualTo("runner-b");
        assertThat(conversation.getExecutionEpoch()).isEqualTo(5L);
    }

    private AgentConversation conversation(String owner, long epoch, LocalDateTime expiresAt) {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conv-a");
        conversation.setStudentId(17);
        conversation.setProjectId(23);
        conversation.setStatus(1);
        conversation.setExecutionOwner(owner);
        conversation.setExecutionEpoch(epoch);
        conversation.setExecutionLeaseExpiresAt(expiresAt);
        return conversation;
    }
}
