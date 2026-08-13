package com.labex.labexagent.run;

/**
 * Immutable proof that an executor holds the active execution lease for one task epoch.
 *
 * <p>A fence binds the durable task identity, the lease owner and the exact execution
 * epoch the worker claimed. It may only be created after the worker owns the task through
 * the lease authority, and it must be re-validated by
 * {@link AgentRunExecutionLeaseService#requireActiveFence} before any durable write.
 *
 * <p>Control-plane lifecycle APIs (queue initialization, transactional dispatch claims,
 * interaction expiry, cancellation and scheduler takeover) are NOT fenced by this value;
 * they remain separately named and validate their own ownership/CAS contract.
 */
public record ExecutionFence(Long taskId, String owner, long epoch) {
}
