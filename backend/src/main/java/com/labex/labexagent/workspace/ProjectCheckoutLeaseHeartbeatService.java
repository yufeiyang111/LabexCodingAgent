package com.labex.labexagent.workspace;

import com.labex.labexagent.runtime.AgentCancellationRegistry;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.stereotype.Service;

/** Renews active checkout leases and interrupts a worker that loses its checkout fencing token. */
@Service
public class ProjectCheckoutLeaseHeartbeatService {
    private static final Logger log = LoggerFactory.getLogger(ProjectCheckoutLeaseHeartbeatService.class);
    private final ProjectCheckoutLeaseService leaseService;
    private final AgentCancellationRegistry cancellationRegistry;
    private final Map<Long, TrackedLease> active = new ConcurrentHashMap<>();

    public ProjectCheckoutLeaseHeartbeatService(ProjectCheckoutLeaseService leaseService,
                                                AgentCancellationRegistry cancellationRegistry) {
        this.leaseService = leaseService;
        this.cancellationRegistry = cancellationRegistry;
    }

    public void track(ProjectCheckoutLeaseService.CheckoutLease lease, String sessionId) {
        if (lease != null && sessionId != null && !sessionId.isBlank()) {
            active.put(lease.taskId(), new TrackedLease(lease, sessionId));
        }
    }

    public void untrack(ProjectCheckoutLeaseService.CheckoutLease lease) {
        if (lease != null) active.remove(lease.taskId());
    }

    @Scheduled(fixedDelayString = "${labex-agent.project-checkout-heartbeat-interval-ms:10000}")
    public void heartbeatScheduled() {
        for (TrackedLease tracked : active.values()) {
            try {
                if (leaseService.renew(tracked.lease())) continue;
            } catch (RuntimeException failure) {
                if (isRetryableFailure(failure)) {
                    log.warn("Project checkout lease heartbeat will retry after transient database failure taskId={} epoch={} reason={}",
                            tracked.lease().taskId(), tracked.lease().epoch(), failure.getMessage());
                    continue;
                }
                log.error("Project checkout lease heartbeat failed unexpectedly taskId={} epoch={}",
                        tracked.lease().taskId(), tracked.lease().epoch(), failure);
            }
            if (active.remove(tracked.lease().taskId(), tracked)) {
                log.warn("Agent run lost project checkout lease taskId={} epoch={}",
                        tracked.lease().taskId(), tracked.lease().epoch());
                cancellationRegistry.cancel(tracked.sessionId());
            }
        }
    }

    private boolean isRetryableFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DataAccessException || current instanceof TransientDataAccessException
                    || current instanceof TransactionSystemException || current instanceof SQLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record TrackedLease(ProjectCheckoutLeaseService.CheckoutLease lease, String sessionId) { }
}
