package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.labexagent.commandsecurity.CommandApprovalResumeScheduler;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.commandsecurity.CommandAuditService;
import com.labex.labexagent.commandsecurity.CommandProcessRecoveryService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AgentRunRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecoveryService.class);
    private static final int COMPACTION_RECOVERY_BATCH_SIZE = 200;
    private static final int COMMAND_PROCESS_RECOVERY_BATCH_SIZE = 100;
    private static final int DEFAULT_LEASE_RECONCILIATION_BATCH_SIZE = 50;
    private static final String INTERRUPTED_COMPACTION_REASON =
            "Agent service restarted without the original compaction execution lease";
    private static final List<String> INTERRUPTED_STATES = List.of(
            "queued", "preparing", "running", "recovering", "waiting_approval", "waiting_user", "waiting_workspace",
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
    private final CommandAuditService commandAuditService;
    private final CommandProcessRecoveryService commandProcessRecoveryService;
    private final CommandApprovalResumeScheduler commandApprovalResumeScheduler;
    private int leaseReconciliationBatchSize;

    public AgentRunRecoveryService(AgentTaskMapper taskMapper, AgentRunLifecycleService lifecycleService,
                                   AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunTakeoverScheduler takeoverScheduler,
                                   AgentRunPartService partService,
                                   AgentRunMessageService messageService,
                                   AgentCompactionService compactionService) {
        this(taskMapper, lifecycleService, executionLeaseService, takeoverScheduler, partService,
                messageService, compactionService, null, null, null, null, null);
    }

    public AgentRunRecoveryService(AgentTaskMapper taskMapper, AgentRunLifecycleService lifecycleService,
                                   AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunTakeoverScheduler takeoverScheduler,
                                   AgentRunPartService partService,
                                   AgentRunMessageService messageService,
                                   AgentCompactionService compactionService,
                                   CommandApprovalService commandApprovalService,
                                   AgentRunTranscriptService transcriptService,
                                   CommandAuditService commandAuditService) {
        this(taskMapper, lifecycleService, executionLeaseService, takeoverScheduler, partService,
                messageService, compactionService, commandApprovalService, transcriptService,
                commandAuditService, null, null);
    }

    @Autowired
    public AgentRunRecoveryService(AgentTaskMapper taskMapper, AgentRunLifecycleService lifecycleService,
                                   AgentRunExecutionLeaseService executionLeaseService,
                                   AgentRunTakeoverScheduler takeoverScheduler,
                                   AgentRunPartService partService,
                                   AgentRunMessageService messageService,
                                   AgentCompactionService compactionService,
                                   CommandApprovalService commandApprovalService,
                                   AgentRunTranscriptService transcriptService,
                                   CommandAuditService commandAuditService,
                                   CommandProcessRecoveryService commandProcessRecoveryService,
                                   CommandApprovalResumeScheduler commandApprovalResumeScheduler) {
        this.taskMapper = taskMapper;
        this.lifecycleService = lifecycleService;
        this.executionLeaseService = executionLeaseService;
        this.takeoverScheduler = takeoverScheduler;
        this.partService = partService;
        this.messageService = messageService;
        this.compactionService = compactionService;
        this.commandApprovalService = commandApprovalService;
        this.transcriptService = transcriptService;
        this.commandAuditService = commandAuditService;
        this.commandProcessRecoveryService = commandProcessRecoveryService;
        this.commandApprovalResumeScheduler = commandApprovalResumeScheduler;
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

    /**
     * 新 JVM 可能在旧命令 lease 过期前启动。启动恢复会保留该 lease；轮询在它过期后领取新 epoch，
     * 再核验并回收持久化进程，避免必须再次重启服务才能继续。
     */
    @Scheduled(fixedDelayString = "${labex-agent.command-approval-resume-poll-interval-ms:1000}")
    public void recoverExpiredCommandProcessesScheduled() {
        recoverExpiredCommandProcesses();
    }

    /**
     * 周期 expired-lease reconciler：启动后按固定间隔运行（而不是只在 ApplicationReadyEvent 扫描一次），
     * 回收 queued/preparing/running/recovering/waiting_workspace/waiting_environment 中租约已过期
     * 且没有活动 owner 的任务。只回收非终态任务；等待态只保留等待原因并记录一次幂等观察事件，
     * 绝不标记完成，也绝不入队交互未解决的任务；其余状态通过 takeover 领取一个新 epoch 并产生
     * 一次幂等 dispatch 事件。每次扫描有界（LIMIT batchSize）。
     */
    @Scheduled(fixedDelayString = "${labex-agent.lease-reconciliation-interval-ms:30000}")
    public int reconcileExpiredExecutionLeasesScheduled() {
        return reconcileExpiredExecutionLeases();
    }

    public int reconcileExpiredExecutionLeases() {
        List<AgentTask> candidates = taskMapper.selectExpiredLeaseCandidates(
                LocalDateTime.now(), effectiveLeaseReconciliationBatchSize());
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int reclaimed = 0;
        for (AgentTask task : candidates) {
            try {
                if (reclaimExpiredLease(task)) {
                    reclaimed++;
                }
            } catch (RuntimeException failure) {
                log.warn("Unable to reclaim expired execution lease taskId={}",
                        task == null ? null : task.getTaskId(), failure);
            }
        }
        return reclaimed;
    }

    private boolean reclaimExpiredLease(AgentTask task) {
        if (task == null || task.getTaskId() == null) {
            return false;
        }
        // 用 claim 时刻的权威行状态派生幂等键：批量查询的快照可能滞后（double-death 竞态下会跨代），
        // 重新读取当前行，使 takeover 从该行 epoch 派生的 key 与 claimDispatch 从锁定行分配的
        // epoch 一致；快照被替换后旧快照不会参与 claim，也就不存在 key/epoch 漂移。
        AgentTask current = taskMapper.selectById(task.getTaskId());
        if (current == null || isTerminal(current.getStatus())) {
            return false;
        }
        if (executionLeaseService.hasActiveLease(current, LocalDateTime.now())) {
            return false;
        }
        AgentRunState state = AgentRunState.fromPersistedStatus(current.getStatus());
        if (state == AgentRunState.WAITING_WORKSPACE || state == AgentRunState.WAITING_ENVIRONMENT
                || state == AgentRunState.WAITING_APPROVAL || state == AgentRunState.WAITING_USER) {
            return preserveWaitingState(current, state);
        }
        return takeoverScheduler.takeover(current);
    }

    /**
     * 等待态只保留等待原因：幂等追加 RUN_RECOVERY_WAITING（固定键，只落一次），不改状态、
     * 不标记完成、不入队。带租约等待态由各自的专属恢复路径（workspace admission / interaction
     * / environment resume）在外部条件满足时通过 lifecycle 权威领取新 dispatch。
     */
    private boolean preserveWaitingState(AgentTask task, AgentRunState state) {
        return lifecycleService.appendEventIfCurrent(
                task.getTaskId(),
                state,
                "RUN_RECOVERY_WAITING",
                Map.of("reason", "Execution lease expired while the task was waiting",
                        "state", state.persistedStatus()),
                "recovery-" + task.getTaskId() + "-waiting") != null;
    }

    @Value("${labex-agent.lease-reconciliation-batch-size:50}")
    void setLeaseReconciliationBatchSize(int batchSize) {
        this.leaseReconciliationBatchSize = batchSize;
    }

    private int effectiveLeaseReconciliationBatchSize() {
        return leaseReconciliationBatchSize > 0 ? leaseReconciliationBatchSize : DEFAULT_LEASE_RECONCILIATION_BATCH_SIZE;
    }

    public int recoverExpiredCommandProcesses() {
        if (commandApprovalService == null || transcriptService == null
                || commandAuditService == null || commandProcessRecoveryService == null
                || commandApprovalResumeScheduler == null) {
            return 0;
        }
        List<CommandApproval> approvals = commandApprovalService
                .findResolvedAgentApprovalsAwaitingResume(COMMAND_PROCESS_RECOVERY_BATCH_SIZE);
        if (approvals == null || approvals.isEmpty()) {
            return 0;
        }

        int recovered = 0;
        for (CommandApproval approval : approvals) {
            if (!isConsumedRecoveryCandidate(approval)) {
                continue;
            }
            AgentRunExecutionLeaseService.ExecutionLease recoveryLease = null;
            CommandApproval recoveredApproval = null;
            try {
                if (recoveryAlreadyClassified(approval)
                        || transcriptService.hasPersistedToolResult(
                        approval.getTaskId(), approval.getToolCallId())) {
                    continue;
                }
                CommandApproval latest = commandApprovalService.findLatestForTask(
                        approval.getStudentId(), approval.getProjectId(), approval.getTaskId());
                if (latest == null || !Objects.equals(latest.getApprovalId(), approval.getApprovalId())) {
                    continue;
                }
                AgentTask task = taskMapper.selectById(approval.getTaskId());
                if (!waitingApproval(task)) {
                    continue;
                }
                recoveryLease = executionLeaseService.acquire(task.getTaskId());
                if (recoveryLease == null) {
                    continue;
                }
                AgentTask claimedTask = taskMapper.selectById(task.getTaskId());
                if (!waitingApproval(claimedTask)
                        || transcriptService.hasPersistedToolResult(
                        approval.getTaskId(), approval.getToolCallId())) {
                    continue;
                }
                recoveredApproval = recoverConsumedCommandExecution(claimedTask);
                if (recoveredApproval != null) {
                    recovered++;
                }
            } catch (RuntimeException failure) {
                log.warn("Unable to retry expired approved command recovery taskId={} approvalId={}",
                        approval.getTaskId(), approval.getApprovalId(), failure);
            } finally {
                if (recoveryLease != null) {
                    executionLeaseService.release(recoveryLease);
                }
            }
            resumeRecoveredCommand(recoveredApproval);
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
        if ((state == AgentRunState.QUEUED || state == AgentRunState.PREPARING || state == AgentRunState.RUNNING
                || state == AgentRunState.RECOVERING) && takeoverScheduler.takeover(task)) return;
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
            resumeRecoveredCommand(recoverConsumedCommandExecution(task));
        }

        if (state == AgentRunState.WAITING_APPROVAL || state == AgentRunState.WAITING_USER
                || state == AgentRunState.WAITING_WORKSPACE || state == AgentRunState.WAITING_ENVIRONMENT
                || state == AgentRunState.WAITING_RECOVERY) {
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
    private CommandApproval recoverConsumedCommandExecution(AgentTask task) {
        if (commandApprovalService == null || transcriptService == null || task == null
                || task.getTaskId() == null || task.getStudentId() == null || task.getProjectId() == null) {
            return null;
        }
        try {
            CommandApproval approval = commandApprovalService.findLatestForTask(
                    task.getStudentId(), task.getProjectId(), task.getTaskId());
            if (!isConsumedRecoveryCandidate(approval)
                    || transcriptService.hasPersistedToolResult(task.getTaskId(), approval.getToolCallId())) {
                return null;
            }
            com.labex.entity.CommandAuditEvent latest = commandAuditService == null
                    ? null : commandAuditService.findLatestExecutionOutcome(approval.getApprovalId());
            if (latest != null && !"claimed".equals(latest.getExecutionStatus())
                    && !"running".equals(latest.getExecutionStatus())) {
                if ("interrupted".equals(latest.getExecutionStatus())
                        && persistRecoveredCommandInterruption(task, approval,
                        CommandProcessRecoveryService.RecoveryResult.ALREADY_INTERRUPTED)) {
                    return approval;
                }
                return null;
            }

            CommandProcessRecoveryService.RecoveryResult recoveryResult = recoverPersistedProcess(latest);
            if (recoveryResult == CommandProcessRecoveryService.RecoveryResult.TERMINATED
                    || recoveryResult == CommandProcessRecoveryService.RecoveryResult.NOT_RUNNING) {
                if (persistRecoveredCommandInterruption(task, approval, recoveryResult)) {
                    return approval;
                }
            }

            String recoveryReason = recoveryResult == null
                    ? "process_identity_not_persisted"
                    : recoveryResult.name().toLowerCase(java.util.Locale.ROOT);
            lifecycleService.appendEventIfCurrent(
                    task.getTaskId(),
                    AgentRunState.WAITING_APPROVAL,
                    "COMMAND_EXECUTION_RECOVERY_UNCERTAIN",
                    Map.of(
                            "approvalId", approval.getApprovalId(),
                            "toolCallId", approval.getToolCallId(),
                            "automaticReplay", false,
                            "processRecovery", recoveryReason,
                            "reason", "The approval was consumed before the command outcome became durable"),
                    commandRecoveryKey(task, approval, "uncertain"));
        } catch (RuntimeException failure) {
            log.warn("Unable to classify consumed command approval after restart taskId={}",
                    task.getTaskId(), failure);
        }
        return null;
    }

    private CommandProcessRecoveryService.RecoveryResult recoverPersistedProcess(
            com.labex.entity.CommandAuditEvent latest) {
        if (commandProcessRecoveryService == null || latest == null
                || latest.getProcessHostId() == null || latest.getProcessHostId().isBlank()
                || latest.getProcessId() == null || latest.getProcessStartEpochMs() == null) {
            return null;
        }
        return commandProcessRecoveryService.recover(latest);
    }

    private boolean persistRecoveredCommandInterruption(
            AgentTask task, CommandApproval approval,
            CommandProcessRecoveryService.RecoveryResult recoveryResult) {
        String detail = "status=interrupted\n"
                + "execution_status=outcome_lost_after_restart\n"
                + "process_recovery=" + recoveryResult.name().toLowerCase(java.util.Locale.ROOT) + "\n"
                + "automatic_replay=false";
        try {
            commandAuditService.recordExecutionInterrupted(
                    approval, "service_restart_process_outcome_lost");
            transcriptService.appendDeferredToolResult(
                    task.getTaskId(), approval.getToolCallId(), "", detail);
        } catch (RuntimeException persistenceFailure) {
            log.warn("Unable to persist recovered command interruption taskId={} approvalId={}",
                    task.getTaskId(), approval.getApprovalId(), persistenceFailure);
            return false;
        }

        try {
            lifecycleService.appendEventIfCurrent(
                    task.getTaskId(),
                    AgentRunState.WAITING_APPROVAL,
                    "COMMAND_EXECUTION_RECOVERY_INTERRUPTED",
                    Map.of(
                            "approvalId", approval.getApprovalId(),
                            "toolCallId", approval.getToolCallId(),
                            "processRecovery", recoveryResult.name().toLowerCase(java.util.Locale.ROOT),
                            "automaticReplay", false,
                            "resumeAgentLoop", true),
                    commandRecoveryKey(task, approval, "interrupted"));
        } catch (RuntimeException eventFailure) {
            log.warn("Unable to append recovered command interruption event taskId={} approvalId={}",
                    task.getTaskId(), approval.getApprovalId(), eventFailure);
        }
        return true;
    }

    private boolean isConsumedRecoveryCandidate(CommandApproval approval) {
        return approval != null
                && approval.getTaskId() != null
                && approval.getStudentId() != null
                && approval.getProjectId() != null
                && approval.getApprovalId() != null
                && approval.getToolCallId() != null
                && !approval.getToolCallId().isBlank()
                && "agent_shell".equals(approval.getSource())
                && "consumed".equals(approval.getStatus());
    }

    private boolean waitingApproval(AgentTask task) {
        return task != null && task.getTaskId() != null
                && AgentRunState.WAITING_APPROVAL.persistedStatus().equals(task.getStatus());
    }

    private boolean recoveryAlreadyClassified(CommandApproval approval) {
        return lifecycleService.hasEvent(approval.getTaskId(),
                commandRecoveryKey(approval.getTaskId(), approval.getApprovalId(), "interrupted"))
                || lifecycleService.hasEvent(approval.getTaskId(),
                commandRecoveryKey(approval.getTaskId(), approval.getApprovalId(), "uncertain"));
    }

    private String commandRecoveryKey(AgentTask task, CommandApproval approval, String outcome) {
        return commandRecoveryKey(task.getTaskId(), approval.getApprovalId(), outcome);
    }

    private String commandRecoveryKey(Long taskId, String approvalId, String outcome) {
        return "recovery-" + taskId + "-command-execution-" + outcome + "-" + approvalId;
    }

    private void resumeRecoveredCommand(CommandApproval approval) {
        if (approval == null || commandApprovalResumeScheduler == null) {
            return;
        }
        try {
            commandApprovalResumeScheduler.resumeIfWaiting(approval);
        } catch (RuntimeException resumeFailure) {
            log.warn("Unable to schedule recovered command continuation taskId={} approvalId={}",
                    approval.getTaskId(), approval.getApprovalId(), resumeFailure);
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
