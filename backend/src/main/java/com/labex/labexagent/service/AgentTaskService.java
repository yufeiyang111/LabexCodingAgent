package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunState;
import com.labex.labexagent.run.AgentRunTransitionKey;
import com.labex.labexagent.run.BackgroundRunWorktreeService;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskService {
    private static final Gson GSON = new Gson();

    private final AgentTaskMapper taskMapper;
    private final AgentChangeSetMapper changeSetMapper;
    private final AgentFileChangeMapper fileChangeMapper;
    private final AgentRunLifecycleService lifecycleService;
    private final BackgroundRunWorktreeService backgroundWorktreeService;

    public AgentTaskService(AgentTaskMapper taskMapper, AgentChangeSetMapper changeSetMapper, AgentFileChangeMapper fileChangeMapper) {
        this(taskMapper, changeSetMapper, fileChangeMapper, null, null);
    }

    @Autowired
    public AgentTaskService(AgentTaskMapper taskMapper, AgentChangeSetMapper changeSetMapper,
                            AgentFileChangeMapper fileChangeMapper,
                            AgentRunLifecycleService lifecycleService) {
        this(taskMapper, changeSetMapper, fileChangeMapper, lifecycleService, null);
    }

    public AgentTaskService(AgentTaskMapper taskMapper, AgentChangeSetMapper changeSetMapper,
                            AgentFileChangeMapper fileChangeMapper, AgentRunLifecycleService lifecycleService,
                            BackgroundRunWorktreeService backgroundWorktreeService) {
        this.taskMapper = taskMapper;
        this.changeSetMapper = changeSetMapper;
        this.fileChangeMapper = fileChangeMapper;
        this.lifecycleService = lifecycleService;
        this.backgroundWorktreeService = backgroundWorktreeService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentTask createTask(Integer studentId, StudentProject project, String conversationId, String sessionId, String mode, String message) {
        return createTask(studentId, project, conversationId, sessionId, mode, message, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentTask createTask(Integer studentId, StudentProject project, String conversationId, String sessionId, String mode, String message, boolean backgroundRun) {
        return createTask(studentId, project, conversationId, sessionId, mode, message, backgroundRun, LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentTask createTask(Integer studentId, StudentProject project, String conversationId, String sessionId,
                                String mode, String message, boolean backgroundRun, LocalDateTime submittedAt) {
        Map<String, Object> payload = this.taskPayload(studentId, project, conversationId, sessionId, mode, message);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveSubmittedAt = submittedAt == null ? now : submittedAt;
        AgentTask task = new AgentTask();
        task.setConversationId(conversationId);
        task.setSessionId(sessionId);
        task.setStudentId(studentId);
        task.setProjectId(project.getProjectId());
        task.setTitle(this.title(message));
        task.setMode(mode);
        task.setStatus(AgentRunState.QUEUED.persistedStatus());
        task.setCurrentStep("\u5206\u6790\u4efb\u52a1");
        task.setSummary("");
        task.setRunVersion(0L);
        task.setLastEventSequence(0L);
        task.setRequestPayload(GSON.toJson(payload));
        task.setRecoveryAttempts(0);
        task.setRetryAttempts(0);
        task.setNextRetryAt(null);
        task.setSubmittedAt(effectiveSubmittedAt);
        task.setActiveElapsedMs(0L);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        this.taskMapper.insert(task);
        if (backgroundRun) {
            if (this.backgroundWorktreeService == null) throw new IllegalStateException("background worktree service is unavailable");
            try {
                var allocation = this.backgroundWorktreeService.allocate(ProjectWorkspace.paths(project).workspaceRoot(), task.getTaskId());
                task.setBackgroundBranch(allocation.branch());
                task.setBackgroundWorktree(allocation.worktree().toString());
                task.setBackgroundBaseRef(allocation.baseRef());
                task.setBackgroundCleanupStatus("active");
                this.taskMapper.updateById(task);
            } catch (Exception e) {
                throw new IllegalStateException("background worktree allocation failed", e);
            }
        }
        if (this.lifecycleService != null) {
            this.lifecycleService.initialize(task, payload, "task-" + task.getTaskId() + "-queued");
        }
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateTask(Long taskId, String status, String currentStep, String summary) {
        if (taskId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (this.isWaitingState(status)) {
            this.pauseTiming(taskId, now);
        }
        AgentRunState runState = this.runState(status);
        if (this.lifecycleService != null && runState != null) {
            this.lifecycleService.transition(
                    taskId,
                    runState,
                    "RUN_STATE_" + runState.name(),
                    this.taskUpdatePayload(status, currentStep, summary),
                    currentStep,
                    summary,
                    AgentRunTransitionKey.forTaskUpdate(taskId, status, currentStep, summary));
            if (this.isTerminalState(status)) {
                this.finishTimingIfTerminal(taskId, now);
            }
            return;
        }
        LambdaUpdateWrapper<AgentTask> update = new LambdaUpdateWrapper<AgentTask>().eq(AgentTask::getTaskId, taskId).set(AgentTask::getUpdateTime, LocalDateTime.now());
        if (status != null) {
            update.set(AgentTask::getStatus, status);
        }
        if (currentStep != null) {
            update.set(AgentTask::getCurrentStep, currentStep);
        }
        if (summary != null) {
            update.set(AgentTask::getSummary, summary);
        }
        this.taskMapper.update(null, update);
    }

    /**
     * Persists cancellation intent before any in-memory cancellation signal is allowed to finalize a run.
     * The transition is idempotent for already-cancelling or already-cancelled tasks.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean requestCancellation(Long taskId, String currentStep, String summary) {
        if (taskId == null) {
            return false;
        }
        if (this.lifecycleService == null) {
            this.updateTask(taskId, "cancelling", currentStep, summary);
            return true;
        }
        AgentTask task = this.task(taskId);
        if (task == null) {
            return false;
        }
        AgentRunState current = this.runState(task.getStatus());
        if (current == AgentRunState.CANCELLING || current == AgentRunState.CANCELLED) {
            return true;
        }
        if (current == null || this.isTerminalState(task.getStatus())) {
            return false;
        }
        return this.lifecycleService.transitionIfCurrent(
                taskId,
                current,
                AgentRunState.CANCELLING,
                "RUN_CANCELLATION_REQUESTED",
                this.taskUpdatePayload("cancelling", currentStep, summary),
                currentStep,
                summary,
                AgentRunTransitionKey.forTaskUpdate(taskId, "cancelling", currentStep, summary));
    }

    /**
     * Finalizes only a persisted cancellation request. A running task is first moved to CANCELLING
     * so callers cannot create the illegal RUNNING -> CANCELLED transition.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeCancellation(Long taskId, String currentStep, String summary) {
        if (taskId == null) {
            return false;
        }
        if (this.lifecycleService == null) {
            this.updateTask(taskId, "cancelled", currentStep, summary);
            return true;
        }
        AgentTask task = this.task(taskId);
        if (task == null) {
            return false;
        }
        AgentRunState current = this.runState(task.getStatus());
        if (current == AgentRunState.CANCELLED) {
            return true;
        }
        if (current != AgentRunState.CANCELLING) {
            if (!this.requestCancellation(taskId, "Cancellation requested", summary)) {
                return false;
            }
            task = this.task(taskId);
            current = task == null ? null : this.runState(task.getStatus());
            if (current == AgentRunState.CANCELLED) {
                return true;
            }
            if (current != AgentRunState.CANCELLING) {
                return false;
            }
        }
        boolean finalized = this.lifecycleService.transitionIfCurrent(
                taskId,
                AgentRunState.CANCELLING,
                AgentRunState.CANCELLED,
                "RUN_CANCELLED",
                this.taskUpdatePayload("cancelled", currentStep, summary),
                currentStep,
                summary,
                AgentRunTransitionKey.forTaskUpdate(taskId, "cancelled", currentStep, summary));
        if (finalized) {
            this.finishTimingIfTerminal(taskId, LocalDateTime.now());
        }
        return finalized;
    }

    /**
     * Moves a resolved user interaction into recovery before a new worker reacquires the project checkout.
     * The run is not marked running until the worker actually owns all required leases.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean beginInteractionResume(Long taskId, String currentStep, String summary) {
        if (taskId == null) return false;
        if (this.lifecycleService == null) {
            this.updateTask(taskId, "recovering", currentStep, summary);
            return true;
        }
        AgentTask task = this.task(taskId);
        if (task == null) return false;
        AgentRunState current = this.runState(task.getStatus());
        if (current == AgentRunState.RECOVERING) return true;
        if (current != AgentRunState.WAITING_USER && current != AgentRunState.WAITING_APPROVAL) return false;
        return this.lifecycleService.transitionIfCurrent(
                taskId,
                current,
                AgentRunState.RECOVERING,
                "RUN_INTERACTION_RESUME_QUEUED",
                this.taskUpdatePayload("recovering", currentStep, summary),
                currentStep,
                summary,
                AgentRunTransitionKey.forTaskUpdate(taskId, "recovering", currentStep, summary));
    }

    /** Places a task behind the active task that owns the same project checkout. */
    @Transactional(rollbackFor = Exception.class)
    public boolean waitForWorkspace(Long taskId, String currentStep, String summary, Long blockingTaskId) {
        return waitForExternalCondition(taskId, AgentRunState.WAITING_WORKSPACE, "waiting_workspace",
                "RUN_WORKSPACE_WAITING", currentStep, summary, Map.of(
                        "blockingTaskId", blockingTaskId == null ? "" : String.valueOf(blockingTaskId)));
    }

    /** Persists an infrastructure blocker rather than letting the model reinterpret a network outage as a code defect. */
    @Transactional(rollbackFor = Exception.class)
    public boolean waitForEnvironment(Long taskId, String currentStep, String summary, String blockerCode) {
        return waitForExternalCondition(taskId, AgentRunState.WAITING_ENVIRONMENT, "waiting_environment",
                "RUN_ENVIRONMENT_BLOCKED", currentStep, summary, Map.of(
                        "blockerCode", blockerCode == null ? "UNKNOWN" : blockerCode));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean beginWorkspaceResume(Long taskId) {
        return resumeExternalCondition(taskId, AgentRunState.WAITING_WORKSPACE, "workspace-resume");
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean beginEnvironmentResume(Long taskId) {
        return resumeExternalCondition(taskId, AgentRunState.WAITING_ENVIRONMENT, "environment-resume");
    }

    private boolean waitForExternalCondition(Long taskId, AgentRunState waitingState, String persistedStatus,
                                             String eventType, String currentStep, String summary,
                                             Map<String, Object> extraPayload) {
        if (taskId == null) return false;
        if (this.lifecycleService == null) {
            this.updateTask(taskId, persistedStatus, currentStep, summary);
            return true;
        }
        AgentTask task = this.task(taskId);
        if (task == null) return false;
        AgentRunState current = this.runState(task.getStatus());
        if (current == waitingState) return true;
        if (current == null || this.isTerminalState(task.getStatus())) return false;
        LocalDateTime now = LocalDateTime.now();
        this.pauseTiming(taskId, now);
        Map<String, Object> payload = new LinkedHashMap<>(this.taskUpdatePayload(persistedStatus, currentStep, summary));
        payload.putAll(extraPayload);
        return this.lifecycleService.transitionIfCurrent(taskId, current, waitingState, eventType, payload,
                currentStep, summary, AgentRunTransitionKey.forTaskUpdate(taskId, persistedStatus, currentStep, summary));
    }

    private boolean resumeExternalCondition(Long taskId, AgentRunState waitingState, String operation) {
        if (taskId == null) return false;
        if (this.lifecycleService == null) {
            this.updateTask(taskId, "queued", "Queued for resume", "External blocker cleared");
            return true;
        }
        AgentTask task = this.task(taskId);
        if (task == null || this.runState(task.getStatus()) != waitingState) return false;
        return this.lifecycleService.transitionIfCurrent(taskId, waitingState, AgentRunState.QUEUED,
                "RUN_" + operation.toUpperCase(java.util.Locale.ROOT).replace('-', '_'),
                this.taskUpdatePayload("queued", "Queued for resume", "External blocker cleared"),
                "Queued for resume", "External blocker cleared",
                operation + "-" + taskId + "-" + longValueOrZero(task.getLastEventSequence()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelRetrySchedule scheduleModelRetry(Long taskId, int maximumAttempts, long delayMs, String reason) {
        if (taskId == null || this.lifecycleService == null) {
            return null;
        }
        AgentTask task = this.task(taskId);
        if (task == null || this.runState(task.getStatus()) != AgentRunState.RUNNING) {
            return null;
        }
        int nextAttempt = valueOrZero(task.getRetryAttempts()) + 1;
        if (nextAttempt > Math.max(0, maximumAttempts)) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        long effectiveDelayMs = Math.max(0L, delayMs);
        LocalDateTime nextRetryAt = now.plusNanos(effectiveDelayMs * 1_000_000L);
        this.pauseTiming(taskId, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt", nextAttempt);
        payload.put("delayMs", effectiveDelayMs);
        payload.put("nextRetryAt", nextRetryAt.toString());
        payload.put("reason", reason == null ? "Transient model failure" : reason);
        boolean scheduled = this.lifecycleService.scheduleModelRetry(
                taskId,
                nextAttempt,
                nextRetryAt,
                payload,
                "Retrying model request",
                "Scheduled model retry " + nextAttempt,
                "model-retry-" + taskId + "-" + nextAttempt);
        if (!scheduled) {
            throw new IllegalStateException("Agent run changed before its retry could be scheduled");
        }
        return new ModelRetrySchedule(nextAttempt, nextRetryAt, effectiveDelayMs);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancelScheduledRetry(Integer studentId, Integer projectId, Long taskId) {
        AgentTask task = getOwnedTask(studentId, projectId, taskId);
        return task != null && lifecycleService != null && lifecycleService.cancelScheduledRetry(taskId,
                Map.of("studentId", studentId, "projectId", projectId), "retry-cancel-" + taskId);
    }

    /** Cancels a durable task that has no in-memory stream, including workspace/environment waits. */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelInactiveRun(Integer studentId, Integer projectId, Long taskId) {
        AgentTask task = getOwnedTask(studentId, projectId, taskId);
        if (task == null) return false;
        AgentRunState state = this.runState(task.getStatus());
        if (state != AgentRunState.RETRYING && state != AgentRunState.WAITING_WORKSPACE
                && state != AgentRunState.WAITING_ENVIRONMENT) {
            return false;
        }
        String summary = "User cancelled inactive agent task";
        return this.requestCancellation(taskId, "Cancellation requested", summary)
                && this.finalizeCancellation(taskId, "Cancelled", summary);
    }

    public void startTiming(Long taskId) {
        this.startTiming(taskId, LocalDateTime.now());
    }

    void startTiming(Long taskId, LocalDateTime now) {
        AgentTask task = this.task(taskId);
        if (task == null || task.getFinishedAt() != null || task.getActiveSegmentStartedAt() != null) {
            return;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        LocalDateTime startedAt = task.getStartedAt() == null ? effectiveNow : task.getStartedAt();
        long activeElapsedMs = this.elapsedValue(task.getActiveElapsedMs());
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .isNull("finished_at")
                .isNull("active_segment_started_at")
                .notIn("status", "completed", "failed", "cancelled")
                .set("active_segment_started_at", effectiveNow)
                .set("active_elapsed_ms", activeElapsedMs)
                .set("update_time", effectiveNow);
        if (task.getStartedAt() == null) {
            update.set("started_at", startedAt);
        }
        if (this.taskMapper.update(null, update) != 1) {
            return;
        }
        task.setStartedAt(startedAt);
        task.setActiveSegmentStartedAt(effectiveNow);
        task.setActiveElapsedMs(activeElapsedMs);
        task.setUpdateTime(effectiveNow);
    }

    public void pauseTiming(Long taskId) {
        this.pauseTiming(taskId, LocalDateTime.now());
    }

    void pauseTiming(Long taskId, LocalDateTime now) {
        AgentTask task = this.task(taskId);
        if (task == null || task.getFinishedAt() != null || task.getActiveSegmentStartedAt() == null) {
            return;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        LocalDateTime activeSegmentStartedAt = task.getActiveSegmentStartedAt();
        long activeElapsedMs = this.elapsedValue(task.getActiveElapsedMs())
                + this.elapsedBetween(activeSegmentStartedAt, effectiveNow);
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .eq("active_segment_started_at", activeSegmentStartedAt)
                .isNull("finished_at")
                .set("active_elapsed_ms", activeElapsedMs)
                .set("active_segment_started_at", null)
                .set("update_time", effectiveNow);
        if (this.taskMapper.update(null, update) != 1) {
            return;
        }
        task.setActiveElapsedMs(activeElapsedMs);
        task.setActiveSegmentStartedAt(null);
        task.setUpdateTime(effectiveNow);
    }

    public void finishTimingIfTerminal(Long taskId) {
        this.finishTimingIfTerminal(taskId, LocalDateTime.now());
    }

    void finishTimingIfTerminal(Long taskId, LocalDateTime now) {
        AgentTask task = this.task(taskId);
        if (task == null || task.getFinishedAt() != null || !this.isTerminalState(task.getStatus())) {
            return;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        long activeElapsedMs = this.elapsedValue(task.getActiveElapsedMs());
        if (task.getActiveSegmentStartedAt() != null) {
            activeElapsedMs += this.elapsedBetween(task.getActiveSegmentStartedAt(), effectiveNow);
        }
        Long elapsedMs = task.getSubmittedAt() == null ? null : this.elapsedBetween(task.getSubmittedAt(), effectiveNow);
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .isNull("finished_at")
                .in("status", "completed", "failed", "cancelled")
                .set("active_elapsed_ms", activeElapsedMs)
                .set("active_segment_started_at", null)
                .set("finished_at", effectiveNow)
                .set("elapsed_ms", elapsedMs)
                .set("update_time", effectiveNow);
        if (this.taskMapper.update(null, update) != 1) {
            return;
        }
        task.setActiveElapsedMs(activeElapsedMs);
        task.setActiveSegmentStartedAt(null);
        task.setFinishedAt(effectiveNow);
        task.setElapsedMs(elapsedMs);
        task.setUpdateTime(effectiveNow);
    }

    private AgentTask task(Long taskId) {
        return taskId == null ? null : this.taskMapper.selectById(taskId);
    }

    private boolean isWaitingState(String status) {
        return "waiting_approval".equalsIgnoreCase(status) || "waiting_user".equalsIgnoreCase(status)
                || "waiting_workspace".equalsIgnoreCase(status) || "waiting_environment".equalsIgnoreCase(status);
    }

    private boolean isTerminalState(String status) {
        return "completed".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long longValueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private long elapsedValue(Long elapsedMs) {
        return elapsedMs == null ? 0L : elapsedMs;
    }

    private long elapsedBetween(LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            return 0L;
        }
        return Duration.between(startedAt, endedAt).toMillis();
    }

    private AgentRunState runState(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AgentRunState.fromPersistedStatus(status);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Map<String, Object> taskPayload(Integer studentId, StudentProject project, String conversationId,
                                             String sessionId, String mode, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", studentId);
        payload.put("projectId", project.getProjectId());
        payload.put("conversationId", conversationId);
        payload.put("sessionId", sessionId);
        payload.put("mode", mode);
        payload.put("message", message);
        return payload;
    }

    private Map<String, Object> taskUpdatePayload(String status, String currentStep, String summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("currentStep", currentStep);
        payload.put("summary", summary);
        return payload;
    }

    public AgentChangeSet getOrCreateOpenChangeSet(Integer studentId, Integer projectId, String conversationId, Long taskId) {
        AgentChangeSet existing = this.changeSetMapper.selectOne(new LambdaQueryWrapper<AgentChangeSet>().eq(AgentChangeSet::getStudentId, studentId).eq(AgentChangeSet::getProjectId, projectId).eq(taskId != null, AgentChangeSet::getTaskId, taskId).eq(AgentChangeSet::getStatus, "pending").last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        AgentChangeSet changeSet = new AgentChangeSet();
        changeSet.setTaskId(taskId);
        changeSet.setConversationId(conversationId);
        changeSet.setStudentId(studentId);
        changeSet.setProjectId(projectId);
        changeSet.setStatus("pending");
        changeSet.setChangeCount(Integer.valueOf(0));
        changeSet.setSummary("\u7b49\u5f85\u786e\u8ba4\u7684\u6587\u4ef6\u4fee\u6539");
        changeSet.setCreateTime(LocalDateTime.now());
        changeSet.setUpdateTime(LocalDateTime.now());
        this.changeSetMapper.insert(changeSet);
        return changeSet;
    }

    public void incrementChangeCount(Long changeSetId) {
        if (changeSetId == null) {
            return;
        }
        List<?> changes = this.fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>().eq(AgentFileChange::getChangeSetId, changeSetId));
        this.changeSetMapper.update(null, new LambdaUpdateWrapper<AgentChangeSet>().eq(AgentChangeSet::getChangeSetId, changeSetId).set(AgentChangeSet::getChangeCount, changes.size()).set(AgentChangeSet::getUpdateTime, LocalDateTime.now()));
    }

    public List<AgentTask> listTasks(Integer studentId, Integer projectId) {
        return this.taskMapper.selectList(new LambdaQueryWrapper<AgentTask>().eq(AgentTask::getStudentId, studentId).eq(AgentTask::getProjectId, projectId).orderByDesc(AgentTask::getUpdateTime).last("LIMIT 30"));
    }

    /** Returns the newest non-terminal task for a conversation so a refreshed browser can reattach. */
    public AgentTask findLatestActiveTask(Integer studentId, Integer projectId, String conversationId) {
        if (studentId == null || projectId == null || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return this.taskMapper.selectOne(new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getStudentId, studentId)
                .eq(AgentTask::getProjectId, projectId)
                .eq(AgentTask::getConversationId, conversationId)
                .notIn(AgentTask::getStatus, List.of("completed", "failed", "cancelled"))
                .orderByDesc(AgentTask::getUpdateTime)
                .last("LIMIT 1"));
    }

    public AgentTask getOwnedTask(Integer studentId, Integer projectId, Long taskId) {
        if (studentId == null || projectId == null || taskId == null) {
            return null;
        }
        return this.taskMapper.selectOne(new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getTaskId, taskId)
                .eq(AgentTask::getStudentId, studentId)
                .eq(AgentTask::getProjectId, projectId));
    }

    public List<AgentFileChange> listPendingChanges(Integer studentId, Integer projectId) {
        return this.fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>().eq(AgentFileChange::getStudentId, studentId).eq(AgentFileChange::getProjectId, projectId).in(AgentFileChange::getStatus, List.of("pending", "applied", "conflicted")).orderByDesc(AgentFileChange::getUpdateTime).last("LIMIT 80"));
    }

    public record ModelRetrySchedule(int attempt, LocalDateTime nextRetryAt, long delayMs) {
    }

    private String title(String message) {
        String text = message == null || message.isBlank() ? "Agent \u4efb\u52a1" : message.trim().replaceAll("\\s+", " ");
        return text.length() > 36 ? text.substring(0, 36) + "..." : text;
    }
}
