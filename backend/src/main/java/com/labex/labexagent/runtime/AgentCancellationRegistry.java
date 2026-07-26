package com.labex.labexagent.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class AgentCancellationRegistry {
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    public void cancel(String sessionId) {
        ActiveRun activeRun = activeRuns.get(sessionId);
        if (activeRun != null) {
            activeRun.requestCancellation();
        }
    }

    /** Looks up an owned run without signalling it, so durable cancellation can be persisted first. */
    public CancellationTarget findCancellationTarget(String sessionId, Integer studentId, Integer projectId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new CancellationTarget(CancellationStatus.NOT_FOUND, null);
        }
        ActiveRun activeRun = activeRuns.get(sessionId);
        if (activeRun == null) {
            return new CancellationTarget(CancellationStatus.NOT_FOUND, null);
        }
        if (!activeRun.belongsTo(studentId, projectId)) {
            return new CancellationTarget(CancellationStatus.FORBIDDEN, null);
        }
        return new CancellationTarget(CancellationStatus.REQUESTED, activeRun);
    }

    public CancellationResult signalCancellation(CancellationTarget target) {
        if (target == null || target.activeRun() == null) {
            return new CancellationResult(target == null ? CancellationStatus.NOT_FOUND : target.status(), null);
        }
        CancellationStatus status = target.activeRun().requestCancellation()
                ? CancellationStatus.REQUESTED
                : CancellationStatus.ALREADY_REQUESTED;
        return new CancellationResult(status, target.activeRun().taskId());
    }

    public CancellationResult cancel(String sessionId, Integer studentId, Integer projectId) {
        return signalCancellation(findCancellationTarget(sessionId, studentId, projectId));
    }

    public ActiveRun register(String sessionId, Integer studentId, Integer projectId, Long taskId) {
        ActiveRun activeRun = new ActiveRun(
                required(sessionId, "sessionId"),
                required(studentId, "studentId"),
                required(projectId, "projectId"),
                taskId);
        ActiveRun previous = activeRuns.put(activeRun.sessionId(), activeRun);
        if (previous != null) {
            previous.requestCancellation();
        }
        return activeRun;
    }

    public void complete(ActiveRun activeRun) {
        if (activeRun == null) {
            return;
        }
        activeRuns.remove(activeRun.sessionId(), activeRun);
        activeRun.clearListeners();
    }

    public boolean isCancelled(String sessionId) {
        ActiveRun activeRun = activeRuns.get(sessionId);
        return activeRun != null && activeRun.isCancellationRequested();
    }

    public void reset(String sessionId) {
        if (sessionId == null) {
            return;
        }
        ActiveRun activeRun = activeRuns.remove(sessionId);
        if (activeRun != null) {
            activeRun.requestCancellation();
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Integer required(Integer value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public enum CancellationStatus {
        REQUESTED,
        ALREADY_REQUESTED,
        NOT_FOUND,
        FORBIDDEN
    }

    public record CancellationResult(CancellationStatus status, Long taskId) {
        public boolean accepted() {
            return status == CancellationStatus.REQUESTED || status == CancellationStatus.ALREADY_REQUESTED;
        }
    }

    public record CancellationTarget(CancellationStatus status, ActiveRun activeRun) {
        public Long taskId() { return activeRun == null ? null : activeRun.taskId(); }
    }

    public static final class ActiveRun implements CancellationToken {
        private final String sessionId;
        private final Integer studentId;
        private final Integer projectId;
        private final Long taskId;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> cancellationListeners = new CopyOnWriteArrayList<>();

        private ActiveRun(String sessionId, Integer studentId, Integer projectId, Long taskId) {
            this.sessionId = sessionId;
            this.studentId = studentId;
            this.projectId = projectId;
            this.taskId = taskId;
        }

        public String sessionId() {
            return sessionId;
        }

        public Long taskId() {
            return taskId;
        }

        @Override
        public boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        @Override
        public Registration onCancellation(Runnable listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener is required");
            }
            AtomicBoolean invoked = new AtomicBoolean();
            Runnable once = () -> {
                if (invoked.compareAndSet(false, true)) {
                    listener.run();
                }
            };
            cancellationListeners.add(once);
            if (isCancellationRequested()) {
                once.run();
            }
            return () -> cancellationListeners.remove(once);
        }

        private boolean belongsTo(Integer requestedStudentId, Integer requestedProjectId) {
            return studentId.equals(requestedStudentId) && projectId.equals(requestedProjectId);
        }

        boolean requestCancellation() {
            if (!cancellationRequested.compareAndSet(false, true)) {
                return false;
            }
            for (Runnable listener : cancellationListeners) {
                try {
                    listener.run();
                } catch (RuntimeException ignored) {
                    // A cancellation listener must not prevent other active operations from stopping.
                }
            }
            return true;
        }

        private void clearListeners() {
            cancellationListeners.clear();
        }
    }
}
