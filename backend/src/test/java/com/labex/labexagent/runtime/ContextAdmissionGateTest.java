package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContextAdmissionGateTest {
    @Test
    void staticOverflowNeverInvokesProviderCallback() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        ContextAdmissionDecision blocked = new ContextAdmissionService().decide(
                new ContextBudgetBreakdown(1000, 800, 200,
                        Map.of("systemPrompt", 500, "toolDefinitions", 350), Map.of()));

        var result = new ContextAdmissionGate().invokeIfAllowed(blocked, () -> {
            invocations.incrementAndGet();
            return "provider-result";
        });

        assertTrue(result.isEmpty());
        assertEquals(0, invocations.get());
    }

    @Test
    void admittedRequestInvokesProviderExactlyOnce() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        ContextBudgetBreakdown breakdown = new ContextBudgetBreakdown(
                1000, 800, 200, Map.of("systemPrompt", 300), Map.of("conversationMessages", 100));
        ContextAdmissionDecision admitted = new ContextAdmissionService().decide(breakdown);

        var result = new ContextAdmissionGate().invokeIfAllowed(admitted, () -> {
            invocations.incrementAndGet();
            return "provider-result";
        });

        assertEquals("provider-result", result.orElseThrow());
        assertEquals(1, invocations.get());
    }
}
