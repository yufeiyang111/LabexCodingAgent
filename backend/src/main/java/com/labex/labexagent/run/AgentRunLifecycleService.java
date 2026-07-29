package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunOutbox;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunLifecycleService {
    private static final Gson GSON = new Gson();
    private static final String OUTBOX_TOPIC = "agent.run.event";

    private final AgentTaskMapper taskMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentRunOutboxMapper outboxMapper;
    private AgentRunPartService partService;

    public AgentRunLifecycleService(AgentTaskMapper taskMapper, AgentRunEventMapper eventMapper,
                                    AgentRunOutboxMapper outboxMapper) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.outboxMapper = outboxMapper;
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
        require(expectedState, "expectedState");
        return transitionInternal(taskId, expectedState, targetState, eventType, payload, currentStep, summary,
                idempotencyKey, Map.of()) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionResult transition(Long taskId, AgentRunState targetState, String eventType,
                                       Object payload, String currentStep, String summary,
                                       String idempotencyKey) {
        TransitionResult result = transitionInternal(taskId, null, targetState, eventType, payload, currentStep,
                summary, idempotencyKey, Map.of());
        if (result == null) {
            throw new IllegalStateException("Agent run state changed concurrently; retry the transition with the same idempotency key");
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public RecoveryClaim claimRecovery(Long taskId, AgentRunState expectedState, String owner, long leaseDurationMs) {
        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null || AgentRunState.fromPersistedStatus(task.getStatus()) != expectedState) return null;
        LocalDateTime now = LocalDateTime.now(); long epoch = valueOrZero(task.getExecutionEpoch()) + 1L; LocalDateTime expires = now.plusNanos(Math.max(5_000L, leaseDurationMs) * 1_000_000L);
        String key = "recovery-takeover-" + taskId + "-" + epoch;
        AgentRunEvent existing = findByIdempotencyKey(taskId, key);
        if (existing != null) return new RecoveryClaim(owner, epoch, expires);
        AgentRunStateMachine.requireTransition(expectedState, AgentRunState.RECOVERING);
        long seq = nextSequence(task);
        long version = valueOrZero(task.getRunVersion()) + 1L;
        UpdateWrapper<AgentTask> update=new UpdateWrapper<AgentTask>().eq("task_id",taskId).eq("status",expectedState.persistedStatus()).eq("run_version",valueOrZero(task.getRunVersion()))
                .and(w->w.isNull("execution_owner").or().isNull("execution_lease_expires_at").or().le("execution_lease_expires_at",now))
                .set("status",AgentRunState.RECOVERING.persistedStatus()).set("last_event_sequence",seq).set("run_version",version).set("execution_owner",owner).set("execution_epoch",epoch).set("execution_lease_expires_at",expires).set("execution_heartbeat_at",now).set("current_step","Recovering after expired lease").set("summary","Recovery takeover claimed").set("update_time",now);
        if(taskMapper.update(null,update)!=1) return null;
        AgentRunEvent event=new AgentRunEvent(); event.setTaskId(taskId);event.setStudentId(task.getStudentId());event.setProjectId(task.getProjectId());event.setSequenceNumber(seq);event.setState("recovering");event.setEventType("RUN_RECOVERY_TAKEOVER");event.setPayload(GSON.toJson(Map.of("owner",owner,"epoch",epoch)));event.setIdempotencyKey(key);event.setCreateTime(now);
        if(eventMapper.insert(event)!=1||event.getEventId()==null) throw new IllegalStateException("Unable to persist recovery takeover event"); persistOutbox(event,Map.of("owner",owner,"epoch",epoch),now); return new RecoveryClaim(owner,epoch,expires);
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
        require(nextRetryAt, "nextRetryAt");
        return transitionInternal(taskId, AgentRunState.RUNNING, AgentRunState.RETRYING,
                "RUN_MODEL_RETRY_SCHEDULED", payload, currentStep, summary, idempotencyKey,
                Map.of("retry_attempts", retryAttempt, "next_retry_at", nextRetryAt)) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean beginScheduledRetry(Long taskId, int retryAttempt, LocalDateTime now, String idempotencyKey) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        Map<String, Object> payload = Map.of("attempt", retryAttempt, "resumedAt", effectiveNow.toString());
        return transitionInternal(taskId, AgentRunState.RETRYING, AgentRunState.RUNNING,
                "RUN_MODEL_RETRY_STARTED", payload, "Retrying model request", "Retry attempt " + retryAttempt,
                idempotencyKey, java.util.Collections.singletonMap("next_retry_at", null)) != null;
    }

    private TransitionResult transitionInternal(Long taskId, AgentRunState expectedState, AgentRunState targetState,
                                                 String eventType, Object payload, String currentStep, String summary,
                                                 String idempotencyKey, Map<String, Object> extraAssignments) {
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
            if (currentState == targetState) {
                return new TransitionResult(existing, false);
            }
            if (expectedState != null) {
                return null;
            }
            throw new IllegalStateException(
                    "Agent run idempotency key was already used while the task is in a different state");
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
                .eq("run_version", expectedVersion)
                .set("status", targetState.persistedStatus())
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
            if (expectedState != null) {
                return null;
            }
            throw new IllegalStateException("Agent run state changed concurrently; retry the transition with the same idempotency key");
        }

        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(task.getTaskId());
        event.setStudentId(task.getStudentId());
        event.setProjectId(task.getProjectId());
        event.setSequenceNumber(nextSequence);
        event.setState(targetState.persistedStatus());
        event.setEventType(eventType);
        event.setPayload(GSON.toJson(payload));
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
        persistOutbox(event, payload, now);
        recordEventPartBestEffort(task.getTaskId(), event.getEventType(), payload, nextSequence);
        return new TransitionResult(event, stateChanged);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunEvent appendEvent(Long taskId, String eventType, Object payload, String idempotencyKey) {
        require(taskId, "taskId");
        require(eventType, "eventType");
        require(idempotencyKey, "idempotencyKey");

        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Agent run not found: " + taskId);
        }
        AgentRunEvent existing = findByIdempotencyKey(taskId, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        AgentRunState state = AgentRunState.fromPersistedStatus(task.getStatus());
        long nextSequence = nextSequence(task);
        long expectedVersion = valueOrZero(task.getRunVersion());
        long nextVersion = expectedVersion + 1L;
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<AgentTask> update = new UpdateWrapper<AgentTask>()
                .eq("task_id", task.getTaskId())
                .eq("status", state.persistedStatus())
                .eq("run_version", expectedVersion)
                .set("last_event_sequence", nextSequence)
                .set("run_version", nextVersion)
                .set("update_time", now);
        if (taskMapper.update(null, update) != 1) {
            throw new IllegalStateException("Agent run changed concurrently; retry the event append with the same idempotency key");
        }

        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(task.getTaskId());
        event.setStudentId(task.getStudentId());
        event.setProjectId(task.getProjectId());
        event.setSequenceNumber(nextSequence);
        event.setState(state.persistedStatus());
        event.setEventType(eventType);
        event.setPayload(GSON.toJson(payload));
        event.setIdempotencyKey(idempotencyKey);
        event.setCreateTime(now);
        if (eventMapper.insert(event) != 1 || event.getEventId() == null) {
            throw new IllegalStateException("Unable to persist agent run event");
        }

        task.setLastEventSequence(nextSequence);
        task.setRunVersion(nextVersion);
        task.setUpdateTime(now);
        persistOutbox(event, payload, now);
        recordEventPartBestEffort(task.getTaskId(), event.getEventType(), payload, nextSequence);
        return event;
    }

    private void recordEventPartBestEffort(Long taskId, String eventType, Object payload, long sequence) {
        if (partService == null) return;
        try {
            partService.recordEventPart(taskId, eventType, payload, sequence);
        } catch (RuntimeException ignored) {
            // Part 投影失败不能回滚已经持久化的生命周期事件。
        }
    }

    private void persistOutbox(AgentRunEvent event, Object payload, LocalDateTime now) {
        AgentRunOutbox outbox = new AgentRunOutbox();
        outbox.setEventId(event.getEventId());
        outbox.setTaskId(event.getTaskId());
        outbox.setTopic(OUTBOX_TOPIC);
        outbox.setPayload(GSON.toJson(outboxPayload(event, payload)));
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

    public record RecoveryClaim(String owner, long epoch, LocalDateTime expiresAt) { }

    public record TransitionResult(AgentRunEvent event, boolean stateChanged) {
    }
}
