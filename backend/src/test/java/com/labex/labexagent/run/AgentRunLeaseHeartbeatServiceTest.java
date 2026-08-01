package com.labex.labexagent.run;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.runtime.AgentCancellationRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionSystemException;

class AgentRunLeaseHeartbeatServiceTest {
    @Test
    void transientDatabaseLockDoesNotDropExecutionLease() {
        AgentRunExecutionLeaseService leaseService = mock(AgentRunExecutionLeaseService.class);
        AgentCancellationRegistry cancellationRegistry = mock(AgentCancellationRegistry.class);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                91L, "owner", 4L, LocalDateTime.now().plusSeconds(30));
        when(leaseService.renew(lease))
                .thenThrow(new CannotAcquireLockException("temporary lock"))
                .thenReturn(true);
        AgentRunLeaseHeartbeatService heartbeat = new AgentRunLeaseHeartbeatService(leaseService, cancellationRegistry);
        heartbeat.track(lease, "session-91");

        heartbeat.heartbeatScheduled();
        heartbeat.heartbeatScheduled();

        verify(leaseService, org.mockito.Mockito.times(2)).renew(lease);
        verify(cancellationRegistry, never()).cancel("session-91");
    }
    @Test
    void transactionRollbackFailureDoesNotDropExecutionLease() {
        AgentRunExecutionLeaseService leaseService = mock(AgentRunExecutionLeaseService.class);
        AgentCancellationRegistry cancellationRegistry = mock(AgentCancellationRegistry.class);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                93L, "owner", 6L, LocalDateTime.now().plusSeconds(30));
        when(leaseService.renew(lease))
                .thenThrow(new TransactionSystemException("rollback failed",
                        new CannotAcquireLockException("temporary lock")))
                .thenReturn(true);
        AgentRunLeaseHeartbeatService heartbeat = new AgentRunLeaseHeartbeatService(leaseService, cancellationRegistry);
        heartbeat.track(lease, "session-93");

        heartbeat.heartbeatScheduled();
        heartbeat.heartbeatScheduled();

        verify(leaseService, org.mockito.Mockito.times(2)).renew(lease);
        verify(cancellationRegistry, never()).cancel("session-93");
    }

}
