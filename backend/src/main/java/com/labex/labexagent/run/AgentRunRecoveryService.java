package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class AgentRunRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecoveryService.class);
    private static final List<String> INTERRUPTED_STATES = List.of(
            "queued", "preparing", "running", "waiting_approval", "waiting_user", "waiting_workspace",
            "waiting_environment", "retrying", "cancelling");

    private final AgentTaskMapper taskMapper;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunExecutionLeaseService executionLeaseService;
    private final AgentRunTakeoverScheduler takeoverScheduler;
    private final AgentRunPartService partService;
    private final AgentRunMessageService messageService;

    @Autowired
    public AgentRunRecoveryService(AgentTaskMapper taskMapper, AgentRunLifecycleService lifecycleService,
                                   AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunTakeoverScheduler takeoverScheduler,
                                   AgentRunPartService partService,
                                   AgentRunMessageService messageService) {
        this.taskMapper = taskMapper;
        this.lifecycleService = lifecycleService;
        this.executionLeaseService = executionLeaseService;
        this.takeoverScheduler = takeoverScheduler;
        this.partService = partService;
        this.messageService = messageService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterStartup() {
        recoverInterruptedRuns();
    }

    public int recoverInterruptedRuns() {
        List<AgentTask> tasks = taskMapper.selectList(new QueryWrapper<AgentTask>()
                .in("status", INTERRUPTED_STATES));
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (AgentTask task : tasks) {
            try {
                recover(task);
                recovered++;
            } catch (Exception e) {
                log.error("Unable to recover interrupted agent run taskId={}", task.getTaskId(), e);
            }
        }
        return recovered;
    }

    private void recover(AgentTask task) {
        if (executionLeaseService.hasActiveLease(task, java.time.LocalDateTime.now())) {
            lifecycleService.appendEvent(
                    task.getTaskId(),
                    "RUN_RECOVERY_ACTIVE_LEASE",
                    Map.of("owner", task.getExecutionOwner(), "epoch", valueOrZero(task.getExecutionEpoch()),
                            "leaseExpiresAt", String.valueOf(task.getExecutionLeaseExpiresAt())),
                    "recovery-" + task.getTaskId() + "-active-lease");
            return;
        }
        AgentRunState state = AgentRunState.fromPersistedStatus(task.getStatus());
        sealInterruptedParts(task, state);
        if ((state == AgentRunState.QUEUED || state == AgentRunState.PREPARING || state == AgentRunState.RUNNING) && takeoverScheduler.takeover(task)) return;
        int attempts = valueOrZero(task.getRecoveryAttempts()) + 1;
        task.setRecoveryAttempts(attempts);
        if (taskMapper.updateById(task) != 1) {
            throw new IllegalStateException("Unable to record agent run recovery attempt");
        }
        Map<String, Object> payload = Map.of(
                "reason", "Agent service restarted before the run could resume",
                "recoveryAttempt", attempts);

        if (state == AgentRunState.WAITING_APPROVAL || state == AgentRunState.WAITING_USER
                || state == AgentRunState.WAITING_WORKSPACE || state == AgentRunState.WAITING_ENVIRONMENT) {
            lifecycleService.appendEvent(
                    task.getTaskId(),
                    "RUN_RECOVERY_WAITING",
                    payload,
                    "recovery-" + task.getTaskId() + "-waiting");
            return;
        }

        if (state == AgentRunState.RETRYING) {
            lifecycleService.appendEvent(
                    task.getTaskId(),
                    "RUN_RECOVERY_RETRY_PENDING",
                    Map.of(
                            "recoveryAttempt", attempts,
                            "retryAttempt", valueOrZero(task.getRetryAttempts()),
                            "nextRetryAt", String.valueOf(task.getNextRetryAt())),
                    "recovery-" + task.getTaskId() + "-retry-pending");
            return;
        }

        if (state == AgentRunState.CANCELLING) {
            lifecycleService.transition(
                    task.getTaskId(),
                    AgentRunState.CANCELLED,
                    "RUN_RECOVERY_CANCELLED",
                    payload,
                    "Recovered cancellation",
                    "Cancellation finalized after service restart",
                    "recovery-" + task.getTaskId() + "-cancelled");
            return;
        }

        lifecycleService.transition(
                task.getTaskId(),
                AgentRunState.FAILED,
                "RUN_RECOVERY_FAILED",
                payload,
                "Recovery required",
                "Agent service restarted before the run could resume",
                "recovery-" + task.getTaskId() + "-failed");
    }

    private void sealInterruptedParts(AgentTask task, AgentRunState state) {
        String reason = "Agent service restarted before this model turn completed";
        partService.interruptOpenParts(task.getTaskId(), reason);
        boolean waiting = state == AgentRunState.WAITING_APPROVAL || state == AgentRunState.WAITING_USER
                || state == AgentRunState.WAITING_WORKSPACE || state == AgentRunState.WAITING_ENVIRONMENT
                || state == AgentRunState.RETRYING;
        messageService.markOpenMessages(task.getTaskId(), waiting ? "waiting" : "interrupted", reason);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
