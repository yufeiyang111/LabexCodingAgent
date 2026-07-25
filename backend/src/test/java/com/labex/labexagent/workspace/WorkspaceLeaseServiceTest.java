package com.labex.labexagent.workspace;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class WorkspaceLeaseServiceTest {

    @Test
    void rejectsAnotherRunWhileTheCheckoutLeaseIsHeld() throws Exception {
        WorkspaceLeaseService leases = new WorkspaceLeaseService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (WorkspaceLeaseService.Lease ignored = leases.acquire("checkout-12", "run-a")) {
                Future<Throwable> result = executor.submit(() -> {
                    try {
                        leases.acquire("checkout-12", "run-b");
                        return null;
                    } catch (Throwable error) {
                        return error;
                    }
                });

                assertInstanceOf(WorkspaceLeaseService.LeaseConflictException.class, result.get());
                assertTrue(leases.isHeldBy("checkout-12", "run-a"));
            }

            try (WorkspaceLeaseService.Lease ignored = leases.acquire("checkout-12", "run-b")) {
                assertTrue(leases.isHeldBy("checkout-12", "run-b"));
            }
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    void releasesLeakedLeasesAfterTheOwnerHasReachedTerminalCleanup() {
        WorkspaceLeaseService leases = new WorkspaceLeaseService();
        leases.acquire("checkout-12", "task:52");
        leases.acquire("checkout-13", "task:52");

        assertTrue(leases.releaseAllOwnedBy("task:52") == 2);
        assertTrue(!leases.isHeldBy("checkout-12", "task:52"));
        assertTrue(!leases.isHeldBy("checkout-13", "task:52"));
        try (WorkspaceLeaseService.Lease ignored = leases.acquire("checkout-12", "task:53")) {
            assertTrue(leases.isHeldBy("checkout-12", "task:53"));
        }
    }

}
