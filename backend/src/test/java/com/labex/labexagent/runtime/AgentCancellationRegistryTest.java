package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentCancellationRegistryTest {

    @Test
    void acceptsOneOwnedCancellationAndNotifiesRegisteredListeners() {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun run = registry.register("session-1", 7, 12, 45L);
        AtomicInteger notifications = new AtomicInteger();

        try (CancellationToken.Registration ignored = run.onCancellation(notifications::incrementAndGet)) {
            assertEquals(AgentCancellationRegistry.CancellationStatus.REQUESTED,
                    registry.cancel("session-1", 7, 12).status());
            assertEquals(AgentCancellationRegistry.CancellationStatus.ALREADY_REQUESTED,
                    registry.cancel("session-1", 7, 12).status());
        }

        assertTrue(run.isCancellationRequested());
        assertEquals(1, notifications.get());
    }

    @Test
    void rejectsCancellationFromAnotherUserOrProject() {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun run = registry.register("session-2", 7, 12, 46L);

        assertEquals(AgentCancellationRegistry.CancellationStatus.FORBIDDEN,
                registry.cancel("session-2", 8, 12).status());
        assertEquals(AgentCancellationRegistry.CancellationStatus.FORBIDDEN,
                registry.cancel("session-2", 7, 13).status());
        assertFalse(run.isCancellationRequested());
    }
    @Test
    void duplicateRegistrationForTheSameDurableTaskIsIdempotentAndCannotImpersonateUserCancellation() {
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun original = registry.register("session-duplicate", 7, 12, 47L);

        AgentCancellationRegistry.ActiveRun duplicate = registry.register("session-duplicate", 7, 12, 47L);

        assertEquals(original, duplicate);
        assertFalse(original.isCancellationRequested());
    }
}

