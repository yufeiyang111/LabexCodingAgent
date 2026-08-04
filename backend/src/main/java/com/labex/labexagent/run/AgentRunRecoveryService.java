package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class AgentRunRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecoveryService.class);
    private static final int COMPACTION_RECOVERY_BATCH_SIZE = 200;
    private static final String INTERRUPTED_COMPACTION_REASON =
            "Agent service restarted without the original compaction execution lease";
    private static final List<String> INTERRUPTED_STATES = List.of(
            "queued", "preparing", "running", "waiting_approval", "waiting_user", "waiting_workspace",
            "waiting_environment", "retrying", "cancelling");

    private final AgentTaskMapper taskMapper;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunExecutionLeaseService executionLeaseService;
    private final AgentRunTakeoverScheduler takeoverScheduler;
    private final AgentRunPartService partService;
    private final AgentRunMessageService messageService;
    private final AgentCompactionService compactionService;
    private final CommandApprovalService commandApprovalService;
    private final AgentRunTranscriptService transcriptService;

    public AgentRunRecoveryService(AgentTaskMapper taskMapper, AgentRunLifecycleService lifecycleService,
                                   AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunTakeoverScheduler takeoverScheduler,
                                   AgentRunPartService partService,
                                   AgentRunMessageService messageService,
                                   AgentCompactionService compactionService) {
        this(taskMapper, lifecycleService, executionLeaseService, takeoverScheduler, partService,
                messageService, compactionService, null, null);
    }

    @Autowired
    public AgentRunRecoveryService(AgentTaskMapper taskMapper, AgentRunLifecycleService lifecycleService,
                                   AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunTakeoverScheduler takeoverScheduler,
                                   AgentRunPartService partService,
                                   AgentRunMessageService messageService,
                                   AgentCompactionService compactionService,
                                   CommandApprovalService commandApprovalService,
                                   AgentRunTranscriptService transcriptService) {
        this.taskMapper = taskMapper;
        this.lifecycleService = lifecycleService;
        this.executionLeaseService = executionLeaseService;
        this.takeoverScheduler = takeoverScheduler;
        this.partService = partService;
        this.messageService = messageService;
        this.compactionService = compactionService;
        this.commandApprovalService = commandApprovalService;
        this.transcriptService = transcriptService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterStartup() {
        recoverInterruptedCompactions();
        recoverInterruptedRuns();
    }

    /**
     * 启动时关闭已经失去原执行租约的 running compaction。
     * 只有 task、execution epoch 与活动租约同时匹配时，才可能是其他实例仍在合法执行。
     */
    public int recoverInterruptedCompactions() {
        long cursor = 0L;
        int recovered = 0;
        while (true) {
            List<AgentCompactionRecord> records = compactionService.runningRecordsAfter(
                    cursor, COMPACTION_RECOVERY_BATCH_SIZE);
            if (records == null || records.isEmpty()) {
                return recovered;
            }
            for (AgentCompactionRecord record : records) {
                Long compactionId = record == null ? null : record.getCompactionId();
                if (compactionId == null || compactionId <= cursor) {
                    log.error("Unable to recover running compaction with invalid cursor id={}", compactionId);
                    return recovered;
                }
                cursor = compactionId;
                try {
                    AgentTask task = taskMapper.selectById(record.getTaskId());
                    if (belongsToActiveExecution(task, record, LocalDateTime.now())) {
                        continue;
                    }
                    compactionService.fail(record, INTERRUPTED_COMPACTION_REASON);
                    recovered++;
                } catch (Exception e) {
                    log.error("Unable to recover interrupted compaction compactionId={} taskId={}",
                            record.getCompactionId(), record.getTaskId(), e);
                }
            }
            if (records.size() < COMPACTION_RECOVERY_BATCH_SIZE) {
                return recovered;
            }
        }
    }

    private boolean belongsToActiveExecution(AgentTask task, AgentCompactionRecord record, LocalDateTime now) {
        if (task == null || isTerminal(task.getStatus())) {
            return false;
        }
        return Objects.equals(valueOrZero(task.getExecutionEpoch()), valueOrZero(record.getExecutionEpoch()))
                && executionLeaseService.hasActiveLease(task, now);
    }

    private boolean isTerminal(String persistedStatus) {
        try {
            AgentRunState state = AgentRunState.fromPersistedStatus(persistedStatus);
            return state == AgentRunState.COMPLETED
                    || state == AgentRunState.FAILED
                    || state == AgentRunState.CANCELLED;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
        AgentRunState state = AgentRunState.fromPersistedStatus(task.getStatus());
        if (executionLeaseService.hasActiveLease(task, java.time.LocalDateTime.now())) {
            lifecycleService.appendEventIfCurrent(
                    task.getTaskId(),
                    state,
                    "RUN_RECOVERY_ACTIVE_LEASE",
                    Map.of("owner", task.getExecutionOwner(), "epoch", valueOrZero(task.getExecutionEpoch()),
                            "leaseExpiresAt", String.valueOf(task.getExecutionLeaseExpiresAt())),
                    "recovery-" + task.getTaskId() + "-active-lease");
            return;
        }
        if ((state == AgentRunState.QUEUED || state == AgentRunState.PREPARING || state == AgentRunState.RUNNING) && takeoverScheduler.takeover(task)) return;
        Integer attemptsValue = lifecycleService.recordRecoveryAttemptIfCurrent(task.getTaskId(), state);
        if (attemptsValue == null) {
            return;
        }
        int attempts = attemptsValue;
        sealInterruptedParts(task, state);
        Map<String, Object> payload = Map.of(
                "reason", "Agent service restarted before the run could resume",
                "recoveryAttempt", attempts);

        if (state == AgentRunState.WAITING_APPROVAL) {
            recordUncertainCommandExecution(task);
        }

        if (state == AgentRunState.WAITING_APPROVAL || state == AgentRunState.WAITING_USER
                || state == AgentRunState.WAITING_WORKSPACE || state == AgentRunState.WAITING_ENVIRONMENT) {
            lifecycleService.appendEventIfCurrent(
                    task.getTaskId(),
                    state,
                    "RUN_RECOVERY_WAITING",
                    payload,
                    "recovery-" + task.getTaskId() + "-waiting");
            return;
        }

        if (state == AgentRunState.RETRYING) {
            lifecycleService.appendEventIfCurrent(
                    task.getTaskId(),
                    state,
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

    /**
     * JVM 重启后只要 approval 已消费但 tool result 尚未落库，就标记为执行结果不确定。
     * 这里严禁自动重放命令；命令副作用可能已经发生，后续只能由明确的人工/治理流程处理。
     */
    private void recordUncertainCommandExecution(AgentTask task) {
        if (commandApprovalService == null || transcriptService == null || task == null
                || task.getTaskId() == null || task.getStudentId() == null || task.getProjectId() == null) {
            return;
        }
        try {
            CommandApproval approval = commandApprovalService.findLatestForTask(
                    task.getStudentId(), task.getProjectId(), task.getTaskId());
            if (approval == null || !"agent_shell".equals(approval.getSource())
                    || !"consumed".equals(approval.getStatus())
                    || approval.getApprovalId() == null || approval.getToolCallId() == null
                    || approval.getToolCallId().isBlank()
                    || transcriptService.hasPersistedToolResult(task.getTaskId(), approval.getToolCallId())) {
                return;
            }
            lifecycleService.appendEventIfCurrent(
                    task.getTaskId(),
                    AgentRunState.WAITING_APPROVAL,
                    "COMMAND_EXECUTION_RECOVERY_UNCERTAIN",
                    Map.of(
                            "approvalId", approval.getApprovalId(),
                            "toolCallId", approval.getToolCallId(),
                            "automaticReplay", false,
                            "reason", "The approval was consumed before the command outcome became durable"),
                    "recovery-" + task.getTaskId() + "-command-execution-uncertain-" + approval.getApprovalId());
        } catch (RuntimeException failure) {
            log.warn("Unable to classify consumed command approval after restart taskId={}",
                    task.getTaskId(), failure);
        }
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
