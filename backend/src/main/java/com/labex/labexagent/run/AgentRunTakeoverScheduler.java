package com.labex.labexagent.run;

import com.labex.entity.AgentTask;
import com.labex.service.StudentProjectService;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AgentRunTakeoverScheduler {
    private final AgentRunExecutionLeaseService leases;
    private final AgentRunLifecycleService lifecycle;
    private final AgentLoopEngine engine;
    private final StudentProjectService projectService;

    public AgentRunTakeoverScheduler(AgentRunExecutionLeaseService leases, AgentRunLifecycleService lifecycle,
                                     @Lazy AgentLoopEngine engine, StudentProjectService projectService) {
        this.leases = leases;
        this.lifecycle = lifecycle;
        this.engine = engine;
        this.projectService = projectService;
    }

    public boolean takeover(AgentTask task) {
        if (task == null || !safe(task.getStatus())) {
            return false;
        }
        AgentRunState expected = AgentRunState.fromPersistedStatus(task.getStatus());
        if (!projectExists(task)) {
            lifecycle.transitionIfCurrent(
                    task.getTaskId(),
                    expected,
                    AgentRunState.FAILED,
                    "RUN_RECOVERY_PROJECT_MISSING",
                    Map.of("reason", "Project no longer exists",
                            "projectId", String.valueOf(task.getProjectId())),
                    "Recovery failed",
                    "The project no longer exists; the interrupted run cannot be resumed.",
                    "recovery-project-missing-" + task.getTaskId());
            return false;
        }
        AgentRunLifecycleService.RecoveryClaim claim = lifecycle.claimRecovery(
                task.getTaskId(), expected, leases.instanceId(), leases.leaseDurationMs());
        if (claim == null) {
            return false;
        }
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                task.getTaskId(), claim.owner(), claim.epoch(), claim.expiresAt());
        if (!hasDurableContinuationContext(task)) {
            return failClaimedTakeover(task, lease,
                    "Missing durable conversation or task ownership metadata");
        }
        try {
            AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task,
                    "An expired execution lease was taken over. Reassess the workspace and continue without replaying uncertain side effects.");
            engine.resume(task.getStudentId(), task.getProjectId(), request, task.getTaskId(), true, lease);
            return true;
        } catch (RuntimeException failure) {
            return failClaimedTakeover(task, lease, "Unable to enqueue recovered task: " + failure.getMessage());
        }
    }

    private boolean failClaimedTakeover(AgentTask task, AgentRunExecutionLeaseService.ExecutionLease lease,
                                        String reason) {
        lifecycle.transition(
                task.getTaskId(),
                AgentRunState.FAILED,
                "RUN_RECOVERY_TAKEOVER_FAILED",
                Map.of("reason", reason),
                "Recovery takeover failed",
                reason,
                "recovery-takeover-failed-" + task.getTaskId() + "-" + lease.epoch());
        leases.release(lease);
        return false;
    }

    private boolean projectExists(AgentTask task) {
        if (task == null || task.getStudentId() == null || task.getProjectId() == null || projectService == null) {
            return false;
        }
        return projectService.getOwnedProject(task.getStudentId(), task.getProjectId()) != null;
    }

    private boolean hasDurableContinuationContext(AgentTask task) {
        return task.getTaskId() != null && task.getStudentId() != null && task.getProjectId() != null
                && task.getConversationId() != null && !task.getConversationId().isBlank();
    }

    private boolean safe(String status) {
        return "queued".equals(status) || "preparing".equals(status) || "running".equals(status);
    }
}
