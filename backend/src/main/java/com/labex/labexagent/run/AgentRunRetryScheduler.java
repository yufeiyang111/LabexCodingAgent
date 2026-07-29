package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Resumes durable model retries after their persisted retry deadline has passed. */
@Service
public class AgentRunRetryScheduler {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRetryScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final AgentTaskMapper taskMapper;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentLoopEngine agentLoopEngine;

    public AgentRunRetryScheduler(AgentTaskMapper taskMapper, AgentRunLifecycleService lifecycleService,
                                  @Lazy AgentLoopEngine agentLoopEngine) {
        this.taskMapper = taskMapper;
        this.lifecycleService = lifecycleService;
        this.agentLoopEngine = agentLoopEngine;
    }

    @Scheduled(fixedDelayString = "${labex-agent.retry-poll-interval-ms:1000}")
    public void resumeScheduled() {
        resumeDueRetries();
    }

    public int resumeDueRetries() {
        return resumeDueRetries(LocalDateTime.now());
    }

    public int resumeDueRetries(LocalDateTime now) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        List<AgentTask> due = taskMapper.selectList(new QueryWrapper<AgentTask>()
                .eq("status", AgentRunState.RETRYING.persistedStatus())
                .isNotNull("next_retry_at")
                .le("next_retry_at", effectiveNow)
                .orderByAsc("next_retry_at")
                .last("LIMIT " + BATCH_SIZE));
        if (due == null || due.isEmpty()) {
            return 0;
        }

        int resumed = 0;
        for (AgentTask task : due) {
            if (resume(task, effectiveNow)) {
                resumed++;
            }
        }
        return resumed;
    }

    private boolean resume(AgentTask task, LocalDateTime now) {
        if (!validTask(task)) {
            return false;
        }
        int attempt = valueOrZero(task.getRetryAttempts());
        if (attempt <= 0) {
            return false;
        }
        boolean claimed = lifecycleService.beginScheduledRetry(
                task.getTaskId(), attempt, now, "model-retry-start-" + task.getTaskId() + "-" + attempt);
        if (!claimed) {
            return false;
        }
        try {
            agentLoopEngine.resume(task.getStudentId(), task.getProjectId(), continuationRequest(task), task.getTaskId(), true);
            return true;
        } catch (RuntimeException exception) {
            log.error("Unable to resume scheduled model retry taskId={}", task.getTaskId(), exception);
            lifecycleService.transition(
                    task.getTaskId(),
                    AgentRunState.FAILED,
                    "RUN_MODEL_RETRY_RESUME_FAILED",
                    java.util.Map.of("attempt", attempt, "reason", exception.getMessage()),
                    "Retry resume failed",
                    "Unable to queue persisted model retry",
                    "model-retry-resume-failed-" + task.getTaskId() + "-" + attempt);
            return false;
        }
    }

    private AgentStreamRequest continuationRequest(AgentTask task) {
        return AgentRunContinuationRequestFactory.fromTask(task,
                "A transient model failure was retried by the scheduler. Reassess the workspace and continue safely without repeating unconfirmed tool calls.");
    }

    private boolean validTask(AgentTask task) {
        return task != null && task.getTaskId() != null && task.getStudentId() != null
                && task.getProjectId() != null && task.getConversationId() != null;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
