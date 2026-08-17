package com.labex.labexagent.runtime;

import java.util.Optional;
import java.util.concurrent.Callable;

/** Provider 调用的最后一道准入边界。 */
@org.springframework.stereotype.Service
public class ContextAdmissionGate {
    public <T> Optional<T> invokeIfAllowed(ContextAdmissionDecision decision, Callable<T> providerInvocation)
            throws Exception {
        if (decision == null || !decision.providerInvocationAllowed()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providerInvocation.call());
    }
}
