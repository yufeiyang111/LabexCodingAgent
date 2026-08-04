package com.labex.labexagent.service;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLeaseHeartbeatService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.service.StudentProjectService;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 将手动上下文压缩作为独立 AgentTask 异步执行，避免阻塞 HTTP 请求线程。
 */
@Service
public class ManualCompactionTaskRunner {
    private final AgentConversationCompactionService compactions;
    private final AgentTaskService tasks;
    private final AgentRunLifecycleService lifecycle;
    private final StudentProjectService projects;
    private final AgentCancellationRegistry cancellations;
    private final AgentRunExecutionLeaseService leases;
    private final AgentRunLeaseHeartbeatService heartbeats;
    private final ExecutorService executor;

    @Autowired
    public ManualCompactionTaskRunner(AgentConversationCompactionService compactions, AgentTaskService tasks,
                                      AgentRunLifecycleService lifecycle, StudentProjectService projects,
                                      AgentCancellationRegistry cancellations,
                                      AgentRunExecutionLeaseService leases,
                                      AgentRunLeaseHeartbeatService heartbeats) {
        this(compactions, tasks, lifecycle, projects, cancellations, leases, heartbeats,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "labex-manual-compaction");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    ManualCompactionTaskRunner(AgentConversationCompactionService compactions, AgentTaskService tasks,
                               AgentRunLifecycleService lifecycle, StudentProjectService projects,
                               AgentCancellationRegistry cancellations,
                               AgentRunExecutionLeaseService leases,
                               AgentRunLeaseHeartbeatService heartbeats,
                               ExecutorService executor) {
        this.compactions = compactions;
        this.tasks = tasks;
        this.lifecycle = lifecycle;
        this.projects = projects;
        this.cancellations = cancellations;
        this.leases = leases;
        this.heartbeats = heartbeats;
        this.executor = executor;
    }

    public AgentTask start(Integer studentId, Integer projectId, String conversationId, Integer modelConfigId) {
        StudentProject project = projects.getOwnedProject(studentId, projectId);
        AgentTask task = tasks.createTask(studentId, project, conversationId, "compact-" + UUID.randomUUID(),
                "compact", "Manual context compaction", false);
        executor.execute(() -> run(studentId, projectId, conversationId, modelConfigId, task));
        return task;
    }

    private void run(Integer studentId, Integer projectId, String conversationId, Integer modelConfigId, AgentTask task) {
        AgentTask current = tasks.getOwnedTask(studentId, projectId, task.getTaskId());
        if (current == null || "cancelled".equalsIgnoreCase(current.getStatus())) {
            return;
        }
        AgentRunExecutionLeaseService.ExecutionLease executionLease = null;
        AgentCancellationRegistry.ActiveRun activeRun = null;
        try {
            executionLease = leases.acquire(task.getTaskId());
            if (executionLease == null) {
                return;
            }
            activeRun = cancellations.register(task.getSessionId(), studentId, projectId, task.getTaskId());
            task.setExecutionEpoch(executionLease.epoch());
            heartbeats.track(executionLease, task.getSessionId());
            tasks.updateTask(task.getTaskId(), "preparing", "Preparing context compaction", "Preparing checkpoint");
            lifecycle.appendEvent(task.getTaskId(), "COMPACTION_STARTED",
                    Map.of("taskId", task.getTaskId(), "sessionId", task.getSessionId(), "strategy", "manual",
                            "modelConfigId", modelConfigId == null ? 0 : modelConfigId),
                    "manual-compaction-start-" + task.getTaskId());
            if (activeRun.isCancellationRequested()) throw new CancellationException("Manual compaction cancelled");
            tasks.updateTask(task.getTaskId(), "running", "Compressing context", "Generating checkpoint");
            lifecycle.appendEvent(task.getTaskId(), "COMPACTION_PROGRESS",
                    Map.of("taskId", task.getTaskId(), "sessionId", task.getSessionId(), "phase", "model_summary"),
                    "manual-compaction-model-" + task.getTaskId());
            AgentConversationCompactionService.Result result = compactions.compact(
                    studentId, projectId, conversationId, modelConfigId, task, activeRun);
            lifecycle.appendEvent(task.getTaskId(), "COMPACTION_COMPLETED", Map.of(
                    "taskId", task.getTaskId(), "sessionId", task.getSessionId(), "strategy", result.strategy(),
                    "deterministicFallback", result.deterministicFallback(),
                    "compactionId", result.compactionId(), "sourceMaxTaskId", result.sourceMaxTaskId(),
                    "legacyProjectionWritten", result.legacyProjectionWritten()),
                    "manual-compaction-complete-" + task.getTaskId());
            tasks.updateTask(task.getTaskId(), "completed", "Context compaction completed", result.strategy());
        } catch (CancellationException cancelled) {
            String reason = "Manual compaction cancelled";
            tasks.requestCancellation(task.getTaskId(), "Cancellation requested", reason);
            lifecycle.appendEvent(task.getTaskId(), "COMPACTION_CANCELLED", Map.of(
                    "taskId", task.getTaskId(), "sessionId", task.getSessionId(), "strategy", "manual", "reason", reason),
                    "manual-compaction-cancelled-" + task.getTaskId());
            tasks.finalizeCancellation(task.getTaskId(), "Context compaction cancelled", reason);
        } catch (Exception failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            lifecycle.appendEvent(task.getTaskId(), "COMPACTION_FAILED", Map.of(
                    "taskId", task.getTaskId(), "sessionId", task.getSessionId(), "strategy", "manual", "reason", reason),
                    "manual-compaction-failed-" + task.getTaskId());
            tasks.updateTask(task.getTaskId(), "failed", "Context compaction failed", reason);
        } finally {
            if (executionLease != null) {
                heartbeats.untrack(executionLease);
                leases.release(executionLease);
            }
            cancellations.complete(activeRun);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
