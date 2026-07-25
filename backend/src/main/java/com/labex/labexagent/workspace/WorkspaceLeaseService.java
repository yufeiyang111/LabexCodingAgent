package com.labex.labexagent.workspace;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Process-local, reentrant single-writer leases for workspace checkouts.
 */
@Service
public class WorkspaceLeaseService {
    private final Map<String, LeaseSlot> slots = new ConcurrentHashMap<>();

    public Lease acquire(String checkoutId, String owner) {
        String key = require(checkoutId, "checkoutId");
        String leaseOwner = require(owner, "owner");
        LeaseSlot slot = slots.computeIfAbsent(key, ignored -> new LeaseSlot());
        synchronized (slot) {
            if (slot.owner == null) {
                slot.owner = leaseOwner;
                slot.holdCount = 1;
                return new Lease(this, key, leaseOwner);
            }
            if (!slot.owner.equals(leaseOwner)) {
                throw new LeaseConflictException(key, slot.owner);
            }
            slot.holdCount++;
            return new Lease(this, key, leaseOwner);
        }
    }

    /**
     * Releases any checkout leases owned by a run that has already reached its AgentLoopEngine cleanup.
     * This is a final safety net for leaked nested leases; it must not be used while the owner is still executing.
     */
    public int releaseAllOwnedBy(String owner) {
        String leaseOwner = require(owner, "owner");
        int released = 0;
        for (var entry : slots.entrySet()) {
            String checkoutId = entry.getKey();
            LeaseSlot slot = entry.getValue();
            synchronized (slot) {
                if (!leaseOwner.equals(slot.owner)) {
                    continue;
                }
                slot.owner = null;
                slot.holdCount = 0;
                if (slots.remove(checkoutId, slot)) {
                    released++;
                }
            }
        }
        return released;
    }

    public boolean isHeldBy(String checkoutId, String owner) {
        LeaseSlot slot = slots.get(checkoutId);
        if (slot == null) {
            return false;
        }
        synchronized (slot) {
            return owner != null && owner.equals(slot.owner);
        }
    }

    public String checkoutId(Integer projectId, Path workspaceRoot) {
        if (projectId == null || workspaceRoot == null) {
            throw new IllegalArgumentException("project checkout is required");
        }
        return "project:" + projectId + ":" + workspaceRoot.toAbsolutePath().normalize();
    }

    private void release(String checkoutId, String owner) {
        LeaseSlot slot = slots.get(checkoutId);
        if (slot == null) {
            return;
        }
        synchronized (slot) {
            if (!owner.equals(slot.owner)) {
                return;
            }
            slot.holdCount--;
            if (slot.holdCount <= 0) {
                slot.owner = null;
                slot.holdCount = 0;
                slots.remove(checkoutId, slot);
            }
        }
    }

    private String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public static final class Lease implements AutoCloseable {
        private final WorkspaceLeaseService service;
        private final String checkoutId;
        private final String owner;
        private boolean closed;

        private Lease(WorkspaceLeaseService service, String checkoutId, String owner) {
            this.service = service;
            this.checkoutId = checkoutId;
            this.owner = owner;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                service.release(checkoutId, owner);
            }
        }
    }

    public static final class LeaseConflictException extends IllegalStateException {
        public LeaseConflictException(String checkoutId, String currentOwner) {
            super("Workspace checkout is busy: " + checkoutId + " is held by " + currentOwner);
        }
    }

    private static final class LeaseSlot {
        private String owner;
        private int holdCount;
    }
}
