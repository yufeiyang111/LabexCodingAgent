package com.labex.labexagent.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentProjectCheckoutLease;
import com.labex.mapper.AgentProjectCheckoutLeaseMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ProjectCheckoutLeaseServiceTest {

    @Test
    void acquiresAnUnownedCheckoutAndUsesWorkspacePathInTheLeaseKey() {
        AgentProjectCheckoutLeaseMapper mapper = mock(AgentProjectCheckoutLeaseMapper.class);
        when(mapper.selectById(any())).thenReturn(null);
        when(mapper.insert(any())).thenReturn(1);
        ProjectCheckoutLeaseService service = new ProjectCheckoutLeaseService(mapper, "node-a", 30_000L);

        var first = service.acquire(71L, 12, Path.of("D:/workspaces/project-a"), LocalDateTime.of(2026, 7, 25, 10, 0));
        var second = service.acquire(72L, 12, Path.of("D:/workspaces/project-a-background"), LocalDateTime.of(2026, 7, 25, 10, 0));

        assertThat(first.acquired()).isTrue();
        assertThat(second.acquired()).isTrue();
        assertThat(first.lease().checkoutKey()).isNotEqualTo(second.lease().checkoutKey());
    }

    @Test
    void reportsTheTaskHoldingAnUnexpiredCheckoutWithoutReplacingIt() {
        AgentProjectCheckoutLeaseMapper mapper = mock(AgentProjectCheckoutLeaseMapper.class);
        AgentProjectCheckoutLease held = new AgentProjectCheckoutLease();
        held.setCheckoutKey("held"); held.setTaskId(59L); held.setLeaseOwner("node-a"); held.setLeaseEpoch(1L);
        held.setLeaseExpiresAt(LocalDateTime.of(2026, 7, 25, 10, 1));
        when(mapper.selectById(any())).thenReturn(held);
        ProjectCheckoutLeaseService service = new ProjectCheckoutLeaseService(mapper, "node-a", 30_000L);

        var result = service.acquire(60L, 12, Path.of("D:/workspaces/project-a"), LocalDateTime.of(2026, 7, 25, 10, 0));

        assertThat(result.acquired()).isFalse();
        assertThat(result.blockingTaskId()).isEqualTo(59L);
        verify(mapper, never()).update(any(), any());
    }
}
