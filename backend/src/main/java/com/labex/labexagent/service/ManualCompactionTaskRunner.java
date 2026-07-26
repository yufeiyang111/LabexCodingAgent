package com.labex.labexagent.service;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
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
    private final AgentConversationService conversations;
    private final AgentTaskService tasks;
    private final AgentRunLifecycleService lifecycle;
    private final StudentProjectService projects;
    private final AgentCancellationRegistry cancellations;
    private final ExecutorService executor;

    @Autowired
    public ManualCompactionTaskRunner(AgentConversationService conversations, AgentTaskService tasks,
                                      AgentRunLifecycleService lifecycle, StudentProjectService projects,
                                      AgentCancellationRegistry cancellations) {
        this(conversations, tasks, lifecycle, projects, cancellations, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "labex-manual-compaction");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ManualCompactionTaskRunner(AgentConversationService conversations, AgentTaskService tasks,
                               AgentRunLifecycleService lifecycle, StudentProjectService projects,
                               AgentCancellationRegistry cancellations, ExecutorService executor) {
        this.conversations = conversations;
        this.tasks = tasks;
        this.lifecycle = lifecycle;
        this.projects = projects;
        this.cancellations = cancellations;
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
        AgentCancellationRegistry.ActiveRun activeRun = cancellations.register(task.getSessionId(), studentId, projectId,
                task.getTaskId());
        try {
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
            AgentConversationService.ManualCompactionResult result = conversations.compactConversation(
                    studentId, projectId, conversationId, modelConfigId, activeRun);
            if (activeRun.isCancellationRequested()) throw new CancellationException("Manual compaction cancelled");
            lifecycle.appendEvent(task.getTaskId(), "COMPACTION_COMPLETED", Map.of(
                    "taskId", task.getTaskId(), "sessionId", task.getSessionId(), "strategy", result.strategy(),
                    "deterministicFallback", result.deterministicFallback()),
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
            cancellations.complete(activeRun);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
