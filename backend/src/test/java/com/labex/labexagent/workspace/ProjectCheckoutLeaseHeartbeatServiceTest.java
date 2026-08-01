package com.labex.labexagent.workspace;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.runtime.AgentCancellationRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionSystemException;

class ProjectCheckoutLeaseHeartbeatServiceTest {
    @Test
    void transientDatabaseLockDoesNotDropCheckoutLease() {
        ProjectCheckoutLeaseService leaseService = mock(ProjectCheckoutLeaseService.class);
        AgentCancellationRegistry cancellationRegistry = mock(AgentCancellationRegistry.class);
        ProjectCheckoutLeaseService.CheckoutLease lease = new ProjectCheckoutLeaseService.CheckoutLease(
                "checkout-key", 92L, "owner", 5L, LocalDateTime.now().plusSeconds(30));
        when(leaseService.renew(lease))
                .thenThrow(new CannotAcquireLockException("temporary lock"))
                .thenReturn(true);
        ProjectCheckoutLeaseHeartbeatService heartbeat =
                new ProjectCheckoutLeaseHeartbeatService(leaseService, cancellationRegistry);
        heartbeat.track(lease, "session-92");

        heartbeat.heartbeatScheduled();
        heartbeat.heartbeatScheduled();

        verify(leaseService, org.mockito.Mockito.times(2)).renew(lease);
        verify(cancellationRegistry, never()).cancel("session-92");
    }
    @Test
    void transactionRollbackFailureDoesNotDropCheckoutLease() {
        ProjectCheckoutLeaseService leaseService = mock(ProjectCheckoutLeaseService.class);
        AgentCancellationRegistry cancellationRegistry = mock(AgentCancellationRegistry.class);
        ProjectCheckoutLeaseService.CheckoutLease lease = new ProjectCheckoutLeaseService.CheckoutLease(
                "checkout-key-2", 94L, "owner", 7L, LocalDateTime.now().plusSeconds(30));
        when(leaseService.renew(lease))
                .thenThrow(new TransactionSystemException("rollback failed",
                        new CannotAcquireLockException("temporary lock")))
                .thenReturn(true);
        ProjectCheckoutLeaseHeartbeatService heartbeat =
                new ProjectCheckoutLeaseHeartbeatService(leaseService, cancellationRegistry);
        heartbeat.track(lease, "session-94");

        heartbeat.heartbeatScheduled();
        heartbeat.heartbeatScheduled();

        verify(leaseService, org.mockito.Mockito.times(2)).renew(lease);
        verify(cancellationRegistry, never()).cancel("session-94");
    }

}
