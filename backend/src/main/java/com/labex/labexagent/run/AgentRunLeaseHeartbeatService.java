package com.labex.labexagent.run;

import com.labex.labexagent.runtime.AgentCancellationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Renews active execution leases and cancels a local run if it loses its fencing token. */
@Service
public class AgentRunLeaseHeartbeatService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunLeaseHeartbeatService.class);
    private final AgentRunExecutionLeaseService leaseService;
    private final AgentCancellationRegistry cancellationRegistry;
    private final Map<Long, TrackedLease> active = new ConcurrentHashMap<>();

    public AgentRunLeaseHeartbeatService(AgentRunExecutionLeaseService leaseService,
                                         AgentCancellationRegistry cancellationRegistry) {
        this.leaseService = leaseService;
        this.cancellationRegistry = cancellationRegistry;
    }

    public void track(AgentRunExecutionLeaseService.ExecutionLease lease, String sessionId) {
        if (lease != null && sessionId != null && !sessionId.isBlank()) {
            active.put(lease.taskId(), new TrackedLease(lease, sessionId));
        }
    }

    public void untrack(AgentRunExecutionLeaseService.ExecutionLease lease) {
        if (lease != null) {
            active.remove(lease.taskId(), new TrackedLease(lease, ""));
            active.remove(lease.taskId());
        }
    }

    @Scheduled(fixedDelayString = "${labex-agent.execution-heartbeat-interval-ms:10000}")
    public void heartbeatScheduled() {
        for (TrackedLease tracked : active.values()) {
            if (leaseService.renew(tracked.lease())) {
                continue;
            }
            if (active.remove(tracked.lease().taskId(), tracked)) {
                log.warn("Agent run lost execution lease taskId={} epoch={}",
                        tracked.lease().taskId(), tracked.lease().epoch());
                cancellationRegistry.cancel(tracked.sessionId());
            }
        }
    }

    private record TrackedLease(AgentRunExecutionLeaseService.ExecutionLease lease, String sessionId) {
    }
}
