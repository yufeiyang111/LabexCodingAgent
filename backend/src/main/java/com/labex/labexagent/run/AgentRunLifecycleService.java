package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentRunOutbox;
import com.labex.entity.AgentTask;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunInteractionMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunLifecycleService.class);
    private static final Gson GSON = new Gson();
    /** 工具 JSON schema 的 null 也是协议数据，TOOL_EXPOSURE 不能在落库时把它省掉。 */
    private static final Gson TOOL_EXPOSURE_GSON = new GsonBuilder().serializeNulls().create();
    private static final String OUTBOX_TOPIC = "agent.run.event";

    private final AgentTaskMapper taskMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentRunOutboxMapper outboxMapper;
    private final AgentRunInteractionMapper interactionMapper;
    private final AgentRunExecutionLeaseService leaseService;
    private AgentRunPartService partService;

    @Autowired
    public AgentRunLifecycleService(AgentTaskMapper taskMapper, AgentRunEventMapper eventMapper,
                                    AgentRunOutboxMapper outboxMapper,
                                    AgentRunInteractionMapper interactionMapper,
                                    AgentRunExecutionLeaseService leaseService) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.outboxMapper = outboxMapper;
        this.interactionMapper = interactionMapper;
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService is required");
    }

    public AgentRunLifecycleService(AgentTaskMapper taskMapper, AgentRunEventMapper eventMapper,
                                    AgentRunOutboxMapper outboxMapper,
                                    AgentRunInteractionMapper interactionMapper) {
        this(taskMapper, eventMapper, outboxMapper, interactionMapper,
                new AgentRunExecutionLeaseService(taskMapper, "legacy-instance", 30_000L));
    }

    public AgentRunLifecycleService(AgentTaskMapper taskMapper, AgentRunEventMapper eventMapper,
                                    AgentRunOutboxMapper outboxMapper) {
        this(taskMapper, eventMapper, outboxMapper, null,
                new AgentRunExecutionLeaseService(taskMapper, "legacy-instance", 30_000L));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPartService(AgentRunPartService partService) {
        this.partService = partService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult initialize(AgentTask task, Object payload, String idempotencyKey) {
        require(task, "task");
        require(task.getTaskId(), "taskId");
        if (AgentRunState.fromPersistedStatus(task.getStatus()) != AgentRunState.QUEUED) {
            throw new IllegalStateException("Agent run must be queued before initialization");
        }
        return transition(
                task.getTaskId(),
                AgentRunState.QUEUED,
                "RUN_QUEUED",
                payload,
                task.getCurrentStep(),
                task.getSummary(),
                idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean transitionIfCurrent(Long taskId, AgentRunState expectedState, AgentRunState targetState,
                                       String eventType, Object payload, String currentStep, String summary,
                                       String idempotencyKey) {
        return transitionIfCurrentResult(taskId, expectedState, targetState, eventType, payload,
                currentStep, summary, idempotencyKey) != null;
    }

    /**
     * Executor-fenced 状态迁移：写入前先验证 {@link ExecutionFence}（owner + 精确 epoch + 未过期 lease），
     * 并把 fence 谓词并入任务行 UPDATE；零行更新返回 typed stale-fence failure，绝不产生部分持久化。
     * 与仅靠事务内预检的 upsert 写入（transcript/message/part/artifact）不同，lifecycle/plan 的
     * fenced 写入把 owner/epoch/active-lease 直接嵌入 UPDATE 谓词。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean transitionIfCurrent(ExecutionFence fence, Long taskId, AgentRunState expectedState,
                                       AgentRunState targetState, String eventType, Object payload,
                                       String currentStep, String summary, String idempotencyKey) {
        return transitionIfCurrentResult(fence, taskId, expectedState, targetState, eventType, payload,
                currentStep, summary, idempotencyKey) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult transitionIfCurrentResult(Long taskId, AgentRunState expectedState,
                                                      AgentRunState targetState, String eventType, Object payload,
                                                      String currentStep, String summary, String idempotencyKey) {
        require(expectedState, "expectedState");
        return transitionInternal(taskId, expectedState, targetState, eventType, payload, currentStep, summary,
                idempotencyKey, Map.of(), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult transitionIfCurrentResult(ExecutionFence fence, Long taskId, AgentRunState expectedState,
                                                      AgentRunState targetState, String eventType, Object payload,
                                                      String currentStep, String summary, String idempotencyKey) {
        requireFence(fence);
        require(expectedState, "expectedState");
        return transitionInternal(taskId, expectedState, targetState, eventType, payload, currentStep, summary,
                idempotencyKey, Map.of(), fence);
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult transition(Long taskId, AgentRunState targetState, String eventType,
                                       Object payload, String currentStep, String summary,
                                       String idempotencyKey) {
        TransitionResult result = transitionInternal(taskId, null, targetState, eventType, payload, currentStep,
                summary, idempotencyKey, Map.of(), null);
        if (result == null) {
            throw new IllegalStateException("Agent run state changed concurrently; retry the transition with the same idempotency key");
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult transition(ExecutionFence fence, Long taskId, AgentRunState targetState,
                                       String eventType, Object payload, String currentStep, String summary,
                                       String idempotencyKey) {
        requireFence(fence);
        TransitionResult result = transitionInternal(taskId, null, targetState, eventType, payload, currentStep,
                summary, idempotencyKey, Map.of(), fence);
        if (result == null) {
            throw new IllegalStateException("Agent run state changed concurrently; retry the transition with the same idempotency key");
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public RecoveryClaim claimRecovery(Long taskId, AgentRunState expectedState, String owner, long leaseDurationMs) {
        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null || AgentRunState.fromPersistedStatus(task.getStatus()) != expectedState) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        long epoch = valueOrZero(task.getExecutionEpoch()) + 1L;
        LocalDateTime expires = now.plusNanos(Math.max(5_000L, leaseDurationMs) * 1_000_000L);
        String eventType = "RUN_RECOVERY_TAKEOVER";
        String key = "recovery-takeover-" + taskId + "-" + epoch;
        AgentRunEvent existing = findByIdempotencyKey(taskId, key);
        if (existing != null) {
            validateIdempotentReplay(existing, AgentRunState.RECOVERING, eventType);
            return null;
        }
        AgentRunStateMachine.requireTransition(expectedState, AgentRunState.RECOVERING);
        long sequence = nextSequence(task);
        long version = valueOrZero(task.getRunVersion()) + 1L;
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .eq("status", expectedState.persistedStatus())
                .eq("run_version", valueOrZero(task.getRunVersion()))
                .and(wrapper -> wrapper.isNull("execution_owner")
                        .or().isNull("execution_lease_expires_at")
                        .or().le("execution_lease_expires_at", now))
                .set("status", AgentRunState.RECOVERING.persistedStatus())
                .set("last_event_sequence", sequence)
                .set("run_version", version)
                .set("execution_owner", owner)
                .set("execution_epoch", epoch)
                .set("execution_lease_expires_at", expires)
                .set("execution_heartbeat_at", now)
                .set("current_step", "Recovering after expired lease")
                .set("summary", "Recovery takeover claimed")
                .set("update_time", now);
        if (taskMapper.update(null, update) != 1) {
            return null;
        }

        Object safePayload = transitionPayload(eventType, Map.of("owner", owner, "epoch", epoch),
                expectedState, AgentRunState.RECOVERING, epoch);
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(taskId);
        event.setStudentId(task.getStudentId());
        event.setProjectId(task.getProjectId());
        event.setSequenceNumber(sequence);
        event.setState(AgentRunState.RECOVERING.persistedStatus());
        event.setEventType(eventType);
        event.setPayload(serializeEventPayload(eventType, safePayload));
        event.setIdempotencyKey(key);
        event.setCreateTime(now);
        if (eventMapper.insert(event) != 1 || event.getEventId() == null) {
            throw new IllegalStateException("Unable to persist recovery takeover event");
        }
        persistOutbox(event, safePayload, now);
        recordEventPartBestEffort(taskId, eventType, safePayload, sequence);
        return new RecoveryClaim(owner, epoch, expires);
    }

    /**
     * 在同一个事务中领取恢复 dispatch：状态、版本、执行租约、事件和 outbox 必须一起成功。
     * 返回 null 表示本次调用没有取得新的 dispatch，不允许调用方再次入队。
     */
    @Transactional(rollbackFor = Exception.class)
    public DispatchClaim claimDispatch(Long taskId, AgentRunState expectedState, AgentRunState targetState,
                                       String eventType, Object payload, String currentStep, String summary,
                                       String idempotencyKey, String owner, long leaseDurationMs) {
        require(taskId, "taskId");
        require(expectedState, "expectedState");
        require(targetState, "targetState");
        require(eventType, "eventType");
        require(idempotencyKey, "idempotencyKey");
        require(owner, "owner");

        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null || AgentRunState.fromPersistedStatus(task.getStatus()) != expectedState) {
            return null;
        }
        AgentRunEvent existing = findByIdempotencyKey(taskId, idempotencyKey);
        if (existing != null) {
            validateIdempotentReplay(existing, targetState, eventType);
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean activeLease = task.getExecutionOwner() != null
                && !task.getExecutionOwner().isBlank()
                && task.getExecutionLeaseExpiresAt() != null
                && task.getExecutionLeaseExpiresAt().isAfter(now);
        if (activeLease) {
            return null;
        }
        AgentRunStateMachine.requireTransition(expectedState, targetState);
        long nextSequence = nextSequence(task);
        long expectedVersion = valueOrZero(task.getRunVersion());
        long nextVersion = expectedVersion + 1L;
        long epoch = valueOrZero(task.getExecutionEpoch()) + 1L;
        LocalDateTime expiresAt = now.plusNanos(Math.max(5_000L, leaseDurationMs) * 1_000_000L);
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .eq("status", expectedState.persistedStatus())
                .eq("run_version", expectedVersion)
                .and(wrapper -> wrapper.isNull("execution_owner")
                        .or().isNull("execution_lease_expires_at")
                        .or().le("execution_lease_expires_at", now))
                .set("status", targetState.persistedStatus())
                .set("last_event_sequence", nextSequence)
                .set("run_version", nextVersion)
                .set("execution_owner", owner)
                .set("execution_epoch", epoch)
                .set("execution_lease_expires_at", expiresAt)
                .set("execution_heartbeat_at", now)
                .set("update_time", now);
        if (currentStep != null) update.set("current_step", currentStep);
        if (summary != null) update.set("summary", summary);
        if (targetState == AgentRunState.QUEUED || targetState == AgentRunState.RECOVERING) {
            update.set("next_retry_at", null);
        }
        if (taskMapper.update(null, update) != 1) {
            return null;
        }

        Object safePayload = transitionPayload(eventType, payload, expectedState, targetState, epoch);
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(task.getTaskId());
        event.setStudentId(task.getStudentId());
        event.setProjectId(task.getProjectId());
        event.setSequenceNumber(nextSequence);
        event.setState(targetState.persistedStatus());
        event.setEventType(eventType);
        event.setPayload(serializeEventPayload(eventType, safePayload));
        event.setIdempotencyKey(idempotencyKey);
        event.setCreateTime(now);
        if (eventMapper.insert(event) != 1 || event.getEventId() == null) {
            throw new IllegalStateException("Unable to persist agent dispatch event");
        }
        persistOutbox(event, safePayload, now);
        recordEventPartBestEffort(taskId, eventType, safePayload, nextSequence);

        task.setStatus(targetState.persistedStatus());
        task.setLastEventSequence(nextSequence);
        task.setRunVersion(nextVersion);
        task.setExecutionOwner(owner);
        task.setExecutionEpoch(epoch);
        task.setExecutionLeaseExpiresAt(expiresAt);
        task.setExecutionHeartbeatAt(now);
        task.setUpdateTime(now);
        if (currentStep != null) task.setCurrentStep(currentStep);
        if (summary != null) task.setSummary(summary);
        if (targetState == AgentRunState.QUEUED || targetState == AgentRunState.RECOVERING) {
            task.setNextRetryAt(null);
        }
        return new DispatchClaim(new AgentRunExecutionLeaseService.ExecutionLease(taskId, owner, epoch, expiresAt));
    }

    /**
     * 恢复交互的唯一事务性 claim 入口：在同一个事务里锁定任务和最新兼容交互，校验所有权、
     * 任务等待态、交互已解决状态、过期、未消费 claim 和幂等键，记录服务端 claim，把任务迁移到
     * RECOVERING 并递增 execution epoch（复用 {@link #claimDispatch} 的租约/事件/outbox 权威路径）。
     * 永久性无效（非最新、已过期、已消费、重复 dispatch、所有权不符）返回 REJECTED，
     * 仅租约忙等瞬时条件返回 DEFERRED；调用方不得凭用户可控 ID 自行重建 claim。
     */
    @Transactional(rollbackFor = Exception.class)
    public InteractionClaimOutcome claimResolvedInteractionDispatch(Long taskId, Integer studentId,
                                                                    Integer projectId, String interactionId,
                                                                    String currentStep, String summary,
                                                                    String idempotencyKey, String owner,
                                                                    long leaseDurationMs) {
        require(taskId, "taskId");
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(interactionId, "interactionId");
        require(idempotencyKey, "idempotencyKey");
        require(owner, "owner");
        if (interactionMapper == null) {
            throw new IllegalStateException("Agent interaction claim is unavailable");
        }
        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null
                || !Objects.equals(task.getStudentId(), studentId)
                || !Objects.equals(task.getProjectId(), projectId)) {
            return InteractionClaimOutcome.rejected();
        }
        AgentRunState current = AgentRunState.fromPersistedStatus(task.getStatus());
        LocalDateTime now = LocalDateTime.now();
        AgentRunInteraction interaction;
        if (current == AgentRunState.WAITING_USER) {
            interaction = interactionMapper.selectResolvedInteractionForUpdate(
                    studentId, projectId, taskId, interactionId,
                    List.of("question"), List.of("answered", "cancelled"), now);
            if (interaction == null) {
                // Task 2.3: config_proposal 是 proposal 权威的等待投影；只有决策路径
                // （AgentProjectConfigProposalService + 本 claim）能恢复它，通用调度器扫描
                // 永远看不到该类型。状态含 timed_out，与 permission/network 的过期语义一致。
                interaction = interactionMapper.selectResolvedInteractionForUpdate(
                        studentId, projectId, taskId, interactionId,
                        List.of(AgentRunInteraction.TYPE_CONFIG_PROPOSAL),
                        List.of(AgentRunInteraction.STATUS_APPROVED,
                                AgentRunInteraction.STATUS_REJECTED, "timed_out"), now);
            }
        } else if (current == AgentRunState.WAITING_APPROVAL) {
            interaction = interactionMapper.selectResolvedInteractionForUpdate(
                    studentId, projectId, taskId, interactionId,
                    List.of("permission", "network"), List.of("approved", "rejected", "timed_out"), now);
        } else {
            return InteractionClaimOutcome.rejected();
        }
        if (interaction == null) {
            return InteractionClaimOutcome.rejected();
        }
        AgentRunEvent existing = findByIdempotencyKey(taskId, idempotencyKey);
        if (existing != null) {
            validateIdempotentReplay(existing, AgentRunState.RECOVERING, "RUN_INTERACTION_RESUME_QUEUED");
            return InteractionClaimOutcome.rejected();
        }
        String claimId = UUID.randomUUID().toString();
        long claimEpoch = valueOrZero(task.getExecutionEpoch()) + 1L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interactionId", interactionId);
        payload.put("claimId", claimId);
        payload.put("claimEpoch", claimEpoch);
        DispatchClaim dispatch = claimDispatch(taskId, current, AgentRunState.RECOVERING,
                "RUN_INTERACTION_RESUME_QUEUED", payload, currentStep, summary, idempotencyKey, owner,
                leaseDurationMs);
        if (dispatch == null) {
            // 旧 worker 租约仍有效：任务迁移未发生，交互行未被触碰，绝不留未消费 claim。
            return InteractionClaimOutcome.deferred();
        }
        // 任务迁移成功后，同一事务内再写交互 claim，使 claim 与 dispatch 原子绑定；
        // epoch 取 lifecycle 返回的权威租约值。CAS 失败必须回滚已提交的任务迁移，
        // 不能留下"已 recovering 但无 claim"的搁浅状态。
        int claimed = interactionMapper.update(null, new UpdateWrapper<AgentRunInteraction>()
                .eq("interaction_id", interaction.getInteractionId())
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("task_id", taskId)
                .and(wrapper -> wrapper.isNull("resume_claim_id").or().isNotNull("resume_consumed_at"))
                .set("resume_claim_id", claimId)
                .set("resume_claim_epoch", dispatch.lease().epoch())
                .set("resume_claimed_at", now)
                .set("resume_consumed_at", null)
                .set("update_time", now));
        if (claimed != 1) {
            throw new IllegalStateException("Unable to bind interaction claim to dispatch");
        }
        interaction.setResumeClaimId(claimId);
        interaction.setResumeClaimEpoch(dispatch.lease().epoch());
        interaction.setResumeClaimedAt(now);
        interaction.setResumeConsumedAt(null);
        interaction.setUpdateTime(now);
        return InteractionClaimOutcome.claimed(dispatch.lease(), interaction);
    }

    /**
     * Task 2.3 决策路径专用的 config_proposal 恢复 claim：使用本实例的 lease identity
     * 与默认租约时长，幂等键与通用交互恢复一致（同一交互重复 claim 命中同一历史事件）。
     */
    @Transactional(rollbackFor = Exception.class)
    public InteractionClaimOutcome claimConfigProposalDispatch(Long taskId, Integer studentId,
                                                               Integer projectId, String interactionId) {
        require(taskId, "taskId");
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(interactionId, "interactionId");
        return claimResolvedInteractionDispatch(taskId, studentId, projectId, interactionId,
                "Resuming after config proposal decision", "A config proposal decision is ready",
                AgentRunTransitionKey.forInteractionResume(taskId, interactionId),
                leaseService.instanceId(), leaseService.leaseDurationMs());
    }
    @Transactional(rollbackFor = Exception.class)
    public boolean beginRecovery(Long taskId, AgentRunState expectedState, Object payload, String idempotencyKey) {
        return transitionInternal(taskId, expectedState, AgentRunState.RECOVERING, "RUN_RECOVERY_TAKEOVER", payload,
                "Recovering after expired lease", "Recovery takeover claimed", idempotencyKey, Map.of()) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancelScheduledRetry(Long taskId, Object payload, String idempotencyKey) {
        return transitionInternal(taskId, AgentRunState.RETRYING, AgentRunState.CANCELLED, "RUN_RETRY_CANCELLED", payload,
                "Retry cancelled", "User cancelled scheduled retry", idempotencyKey,
                java.util.Collections.singletonMap("next_retry_at", null)) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean scheduleModelRetry(Long taskId, int retryAttempt, LocalDateTime nextRetryAt,
                                      Object payload, String currentStep, String summary, String idempotencyKey) {
        return scheduleModelRetryResult(taskId, retryAttempt, nextRetryAt, payload,
                currentStep, summary, idempotencyKey) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult scheduleModelRetryResult(Long taskId, int retryAttempt, LocalDateTime nextRetryAt,
                                                     Object payload, String currentStep, String summary,
                                                     String idempotencyKey) {
        require(nextRetryAt, "nextRetryAt");
        return transitionInternal(taskId, AgentRunState.RUNNING, AgentRunState.RETRYING,
                "RUN_MODEL_RETRY_SCHEDULED", payload, currentStep, summary, idempotencyKey,
                Map.of("retry_attempts", retryAttempt, "next_retry_at", nextRetryAt), null);
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult scheduleModelRetryResult(ExecutionFence fence, Long taskId, int retryAttempt,
                                                     LocalDateTime nextRetryAt, Object payload, String currentStep,
                                                     String summary, String idempotencyKey) {
        requireFence(fence);
        require(nextRetryAt, "nextRetryAt");
        return transitionInternal(taskId, AgentRunState.RUNNING, AgentRunState.RETRYING,
                "RUN_MODEL_RETRY_SCHEDULED", payload, currentStep, summary, idempotencyKey,
                Map.of("retry_attempts", retryAttempt, "next_retry_at", nextRetryAt), fence);
    }

    @Transactional(rollbackFor = Exception.class)
    public DispatchClaim claimScheduledRetry(Long taskId, int retryAttempt, LocalDateTime now, String idempotencyKey,
                                             String owner, long leaseDurationMs) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        Map<String, Object> payload = Map.of("attempt", retryAttempt, "resumedAt", effectiveNow.toString());
        return claimDispatch(taskId, AgentRunState.RETRYING, AgentRunState.RECOVERING,
                "RUN_MODEL_RETRY_STARTED", payload, "Claiming model retry", "Retry attempt " + retryAttempt,
                idempotencyKey, owner, leaseDurationMs);
    }

    private TransitionResult transitionInternal(Long taskId, AgentRunState expectedState, AgentRunState targetState,
                                                 String eventType, Object payload, String currentStep, String summary,
                                                 String idempotencyKey, Map<String, Object> extraAssignments) {
        return transitionInternal(taskId, expectedState, targetState, eventType, payload, currentStep, summary,
                idempotencyKey, extraAssignments, null);
    }

    private TransitionResult transitionInternal(Long taskId, AgentRunState expectedState, AgentRunState targetState,
                                                 String eventType, Object payload, String currentStep, String summary,
                                                 String idempotencyKey, Map<String, Object> extraAssignments,
                                                 ExecutionFence fence) {
        require(taskId, "taskId");
        require(targetState, "targetState");
        require(eventType, "eventType");
        require(idempotencyKey, "idempotencyKey");

        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Agent run not found: " + taskId);
        }
        AgentRunState currentState = AgentRunState.fromPersistedStatus(task.getStatus());
        AgentRunEvent existing = findByIdempotencyKey(taskId, idempotencyKey);
        if (existing != null) {
            validateIdempotentReplay(existing, targetState, eventType);
            if (currentState == targetState) {
                return new TransitionResult(existing, false);
            }
            if (expectedState != null) {
                return null;
            }
            // 同一幂等键的事件已经持久化时，过期重试只能返回历史结果，不能因任务已前进而再次失败或复活任务。
            return new TransitionResult(existing, false);
        }
        if (expectedState != null && currentState != expectedState) {
            return null;
        }

        boolean stateChanged = currentState != targetState;
        if (stateChanged) {
            AgentRunStateMachine.requireTransition(currentState, targetState);
        }

        long nextSequence = nextSequence(task);
        long expectedVersion = valueOrZero(task.getRunVersion());
        long nextVersion = expectedVersion + 1L;
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", task.getTaskId())
                .eq("status", currentState.persistedStatus())
                .eq("run_version", expectedVersion);
        if (fence != null) {
            update.eq("execution_owner", fence.owner())
                    .eq("execution_epoch", fence.epoch())
                    .gt("execution_lease_expires_at", now);
        }
        update.set("status", targetState.persistedStatus())
                .set("last_event_sequence", nextSequence)
                .set("run_version", nextVersion)
                .set("update_time", now);
        if (currentStep != null) {
            update.set("current_step", currentStep);
        }
        if (summary != null) {
            update.set("summary", summary);
        }
        if (extraAssignments != null) {
            extraAssignments.forEach(update::set);
        }
        if (taskMapper.update(null, update) != 1) {
            if (fence != null) {
                throw new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                        AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_FENCE);
            }
            if (expectedState != null) {
                return null;
            }
            throw new IllegalStateException("Agent run state changed concurrently; retry the transition with the same idempotency key");
        }

        Object safePayload = stateChanged
                ? transitionPayload(eventType, payload, currentState, targetState,
                        valueOrZero(task.getExecutionEpoch()))
                : InternalReasoningBoundary.sanitizeEventPayload(eventType, payload);
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(task.getTaskId());
        event.setStudentId(task.getStudentId());
        event.setProjectId(task.getProjectId());
        event.setSequenceNumber(nextSequence);
        event.setState(targetState.persistedStatus());
        event.setEventType(eventType);
        event.setPayload(serializeEventPayload(eventType, safePayload));
        event.setIdempotencyKey(idempotencyKey);
        event.setCreateTime(now);
        if (eventMapper.insert(event) != 1 || event.getEventId() == null) {
            throw new IllegalStateException("Unable to persist agent run event");
        }

        task.setStatus(targetState.persistedStatus());
        task.setLastEventSequence(nextSequence);
        task.setRunVersion(nextVersion);
        if (currentStep != null) {
            task.setCurrentStep(currentStep);
        }
        if (summary != null) {
            task.setSummary(summary);
        }
        if (extraAssignments != null) {
            Object retryAttempts = extraAssignments.get("retry_attempts");
            if (retryAttempts instanceof Integer value) {
                task.setRetryAttempts(value);
            }
            if (extraAssignments.containsKey("next_retry_at")) {
                task.setNextRetryAt((LocalDateTime) extraAssignments.get("next_retry_at"));
            }
        }
        task.setUpdateTime(now);
        persistOutbox(event, safePayload, now);
        recordEventPartBestEffort(task.getTaskId(), event.getEventType(), safePayload, nextSequence);
        if (stateChanged) {
            log.debug("AGENT_RUN_TRANSITION taskId={} eventType={} previousState={} nextState={} epoch={} sequence={} idempotencyKey={}",
                    task.getTaskId(), eventType, currentState.persistedStatus(), targetState.persistedStatus(),
                    valueOrZero(task.getExecutionEpoch()), nextSequence, idempotencyKey);
        }
        return new TransitionResult(event, stateChanged);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunEvent appendEvent(Long taskId, String eventType, Object payload, String idempotencyKey) {
        return appendEventIfCurrent(taskId, null, eventType, payload, idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunEvent appendEvent(ExecutionFence fence, Long taskId, String eventType, Object payload,
                                     String idempotencyKey) {
        requireFence(fence);
        return appendEventIfCurrentInternal(fence, taskId, null, eventType, payload, idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public Integer recordRecoveryAttemptIfCurrent(Long taskId, AgentRunState expectedState) {
        require(taskId, "taskId");
        require(expectedState, "expectedState");

        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null || AgentRunState.fromPersistedStatus(task.getStatus()) != expectedState) {
            return null;
        }
        int attempts = (task.getRecoveryAttempts() == null ? 0 : task.getRecoveryAttempts()) + 1;
        long expectedVersion = valueOrZero(task.getRunVersion());
        int updated = taskMapper.update(null, new UpdateWrapper<AgentTask>()
                .eq("task_id", taskId)
                .eq("status", expectedState.persistedStatus())
                .eq("run_version", expectedVersion)
                .set("recovery_attempts", attempts)
                .set("update_time", LocalDateTime.now()));
        return updated == 1 ? attempts : null;
    }

    /**
     * 只有任务仍处于预期状态时，才能追加恢复事件，避免旧快照污染终态任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunEvent appendEventIfCurrent(Long taskId, AgentRunState expectedState,
                                              String eventType, Object payload, String idempotencyKey) {
        return appendEventIfCurrentInternal(null, taskId, expectedState, eventType, payload, idempotencyKey);
    }

    /**
     * Executor-fenced 事件追加：写入前验证 {@link ExecutionFence}，并把 owner/epoch/未过期 lease 谓词
     * 并入任务行 UPDATE；零行更新返回 typed stale-fence failure。
     * 与仅靠事务内预检的 upsert 写入（transcript/message/part/artifact）不同，lifecycle/plan 的
     * fenced 写入把 owner/epoch/active-lease 直接嵌入 UPDATE 谓词。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunEvent appendEventIfCurrent(ExecutionFence fence, Long taskId, AgentRunState expectedState,
                                              String eventType, Object payload, String idempotencyKey) {
        requireFence(fence);
        return appendEventIfCurrentInternal(fence, taskId, expectedState, eventType, payload, idempotencyKey);
    }

    private AgentRunEvent appendEventIfCurrentInternal(ExecutionFence fence, Long taskId,
                                                       AgentRunState expectedState,
                                                       String eventType, Object payload, String idempotencyKey) {
        require(taskId, "taskId");
        require(eventType, "eventType");
        require(idempotencyKey, "idempotencyKey");

        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Agent run not found: " + taskId);
        }
        AgentRunState state = AgentRunState.fromPersistedStatus(task.getStatus());
        if (expectedState != null && state != expectedState) {
            return null;
        }
        AgentRunEvent existing = findByIdempotencyKey(taskId, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        long nextSequence = nextSequence(task);
        long expectedVersion = valueOrZero(task.getRunVersion());
        long nextVersion = expectedVersion + 1L;
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", task.getTaskId())
                .eq("status", state.persistedStatus())
                .eq("run_version", expectedVersion);
        if (fence != null) {
            update.eq("execution_owner", fence.owner())
                    .eq("execution_epoch", fence.epoch())
                    .gt("execution_lease_expires_at", now);
        }
        update.set("last_event_sequence", nextSequence)
                .set("run_version", nextVersion)
                .set("update_time", now);
        if (taskMapper.update(null, update) != 1) {
            if (fence != null) {
                throw new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                        AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.STALE_FENCE);
            }
            throw new IllegalStateException("Agent run changed concurrently; retry the event append with the same idempotency key");
        }

        Object safePayload = InternalReasoningBoundary.sanitizeEventPayload(eventType, payload);
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(task.getTaskId());
        event.setStudentId(task.getStudentId());
        event.setProjectId(task.getProjectId());
        event.setSequenceNumber(nextSequence);
        event.setState(state.persistedStatus());
        event.setEventType(eventType);
        event.setPayload(serializeEventPayload(eventType, safePayload));
        event.setIdempotencyKey(idempotencyKey);
        event.setCreateTime(now);
        if (eventMapper.insert(event) != 1 || event.getEventId() == null) {
            throw new IllegalStateException("Unable to persist agent run event");
        }

        task.setLastEventSequence(nextSequence);
        task.setRunVersion(nextVersion);
        task.setUpdateTime(now);
        persistOutbox(event, safePayload, now);
        recordEventPartBestEffort(task.getTaskId(), event.getEventType(), safePayload, nextSequence);
        return event;
    }

    /** 状态迁移审计由唯一生命周期写入入口生成，调用方 payload 不能覆盖。 */
    private Object transitionPayload(String eventType, Object payload, AgentRunState previousState,
                                     AgentRunState nextState, long executionEpoch) {
        Object sanitized = InternalReasoningBoundary.sanitizeEventPayload(eventType, payload);
        LinkedHashMap<String, Object> enriched = new LinkedHashMap<>();
        if (sanitized instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (entry.getKey() != null) {
                    enriched.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else if (sanitized != null) {
            enriched.put("data", sanitized);
        }
        String reason = eventType;
        Object payloadReason = enriched.get("reason");
        if (payloadReason != null && !String.valueOf(payloadReason).isBlank()) {
            reason = String.valueOf(payloadReason);
        }
        LinkedHashMap<String, Object> transition = new LinkedHashMap<>();
        transition.put("previousState", previousState.persistedStatus());
        transition.put("nextState", nextState.persistedStatus());
        transition.put("actor", "agent_run_lifecycle");
        transition.put("reason", reason);
        transition.put("executionEpoch", Math.max(0L, executionEpoch));
        transition.put("stateChanged", true);
        enriched.put("transition", Map.copyOf(transition));
        return enriched;
    }

    private void recordEventPartBestEffort(Long taskId, String eventType, Object payload, long sequence) {
        if (partService == null) return;
        try {
            partService.recordEventPart(taskId, eventType, payload, sequence);
        } catch (RuntimeException error) {
            // 即时投影只是低延迟优化；持久化 outbox 会在广播前重试并修复 transcript。
            log.warn("Agent run transcript inline projection failed; outbox will retry taskId={}, eventType={}, sequence={}",
                    taskId, eventType, sequence, error);
        }
    }

    /** 仅工具暴露快照保留 schema 中显式 null；其他既有运行事件维持原有序列化兼容性。 */
    private String serializeEventPayload(String eventType, Object payload) {
        return "TOOL_EXPOSURE".equals(eventType)
                ? TOOL_EXPOSURE_GSON.toJson(payload)
                : GSON.toJson(payload);
    }

    private void persistOutbox(AgentRunEvent event, Object payload, LocalDateTime now) {
        AgentRunOutbox outbox = new AgentRunOutbox();
        outbox.setEventId(event.getEventId());
        outbox.setTaskId(event.getTaskId());
        outbox.setTopic(OUTBOX_TOPIC);
        outbox.setPayload(serializeEventPayload(event.getEventType(), outboxPayload(event, payload)));
        outbox.setStatus("pending");
        outbox.setAttempts(0);
        outbox.setAvailableTime(now);
        outbox.setCreateTime(now);
        if (outboxMapper.insert(outbox) != 1) {
            throw new IllegalStateException("Unable to persist agent run outbox message");
        }
    }

    private Map<String, Object> outboxPayload(AgentRunEvent event, Object payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", event.getEventId());
        message.put("taskId", event.getTaskId());
        message.put("sequence", event.getSequenceNumber());
        message.put("state", event.getState());
        message.put("eventType", event.getEventType());
        message.put("payload", payload);
        return message;
    }

    private void validateIdempotentReplay(AgentRunEvent existing, AgentRunState targetState, String eventType) {
        boolean sameState = targetState.persistedStatus().equalsIgnoreCase(String.valueOf(existing.getState()));
        boolean sameEventType = eventType.equals(existing.getEventType());
        if (!sameState || !sameEventType) {
            throw new IllegalStateException(
                    "Agent run idempotency key collision: the existing event represents a different transition");
        }
    }

    /** 返回任务已经提交的最大持久事件序号。 */
    public long currentEventSequence(Long taskId) {
        if (taskId == null || taskId <= 0L) return 0L;
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("Agent task not found: " + taskId);
        return Math.max(valueOrZero(task.getLastEventSequence()),
                valueOrZero(eventMapper.selectMaxSequenceByTaskId(taskId)));
    }

    /** 读取实时游标之后、目标序号之前（含目标序号）的已提交事件。 */
    public java.util.List<AgentRunEvent> eventsAfter(Long taskId, long afterExclusive, long throughInclusive) {
        if (taskId == null || taskId <= 0L || throughInclusive <= afterExclusive) {
            return java.util.List.of();
        }
        java.util.List<AgentRunEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<AgentRunEvent>()
                        .eq(AgentRunEvent::getTaskId, taskId)
                        .gt(AgentRunEvent::getSequenceNumber, Math.max(0L, afterExclusive))
                        .le(AgentRunEvent::getSequenceNumber, throughInclusive)
                        .orderByAsc(AgentRunEvent::getSequenceNumber)
                        .last("LIMIT 1000"));
        return events == null ? java.util.List.of() : java.util.List.copyOf(events);
    }

    /** 查询某个持久事件是否已经完成投影，供恢复调度器抑制重复副作用。 */
    public boolean hasEvent(Long taskId, String idempotencyKey) {
        return taskId != null && taskId > 0L
                && idempotencyKey != null && !idempotencyKey.isBlank()
                && findByIdempotencyKey(taskId, idempotencyKey) != null;
    }

    private AgentRunEvent findByIdempotencyKey(Long taskId, String idempotencyKey) {
        return eventMapper.selectOne(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId)
                .eq(AgentRunEvent::getIdempotencyKey, idempotencyKey));
    }

    private long nextSequence(AgentTask task) {
        long taskCursor = valueOrZero(task.getLastEventSequence());
        long persistedMaximum = valueOrZero(eventMapper.selectMaxSequenceByTaskId(task.getTaskId()));
        return Math.max(taskCursor, persistedMaximum) + 1L;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private void require(Object value, String name) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private void requireFence(ExecutionFence fence) {
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for executor-originated writes");
        }
        leaseService.requireActiveFence(fence, LocalDateTime.now());
    }

    public record DispatchClaim(AgentRunExecutionLeaseService.ExecutionLease lease) { }

    /** 服务端拥有的交互恢复 claim 结果：CLAIMED 携带已验证交互和租约，DEFERRED 可稍后重试，REJECTED 永久失效。 */
    public record InteractionClaimOutcome(Outcome outcome,
                                          AgentRunExecutionLeaseService.ExecutionLease lease,
                                          AgentRunInteraction interaction) {
        public enum Outcome {
            CLAIMED,
            DEFERRED,
            REJECTED
        }

        public static InteractionClaimOutcome claimed(AgentRunExecutionLeaseService.ExecutionLease lease,
                                                      AgentRunInteraction interaction) {
            return new InteractionClaimOutcome(Outcome.CLAIMED, lease, interaction);
        }

        public static InteractionClaimOutcome deferred() {
            return new InteractionClaimOutcome(Outcome.DEFERRED, null, null);
        }

        public static InteractionClaimOutcome rejected() {
            return new InteractionClaimOutcome(Outcome.REJECTED, null, null);
        }

        public boolean claimed() {
            return outcome == Outcome.CLAIMED;
        }
    }

    public record RecoveryClaim(String owner, long epoch, LocalDateTime expiresAt) { }

    public record TransitionResult(AgentRunEvent event, boolean stateChanged) {
    }
}
