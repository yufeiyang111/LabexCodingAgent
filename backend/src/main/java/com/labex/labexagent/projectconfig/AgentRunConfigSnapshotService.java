package com.labex.labexagent.projectconfig;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentRunConfigSnapshot;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunConfigurationException;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.mapper.AgentRunConfigSnapshotMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * Durable task-epoch configuration snapshot authority.
 *
 * <p>Each {@code taskId + executionEpoch} owns one immutable {@code t_agent_run_config_snapshot}
 * row. New tasks get their snapshot before entering {@code queued} (epoch zero); resume and
 * takeover copy the same effective revision into a NEW epoch row only after the fence is
 * claimed, and the prior epoch row is never mutated or overwritten. Legacy active tasks without
 * a snapshot receive a migration snapshot built from the exact persisted model reference and
 * fail closed when any reference cannot be resolved.
 *
 * <p>Consumption boundary: at this phase the snapshot is the durable, reconstructible record of
 * the effective configuration; the epoch-scoped runtime resolution that feeds Provider requests
 * (catalog, policy, tool schema) consumes it in the capability phase (Tasks 3.x).
 */
@Component
public class AgentRunConfigSnapshotService {

    /** Epoch used for the pre-queue snapshot of a newly created task. */
    public static final long INITIAL_EPOCH = 0L;

    private final AgentRunConfigSnapshotMapper snapshotMapper;
    private final AgentEffectiveProjectConfigService effectiveConfigService;
    private final AgentRunExecutionLeaseService executionLeaseService;

    public AgentRunConfigSnapshotService(AgentRunConfigSnapshotMapper snapshotMapper,
                                         AgentEffectiveProjectConfigService effectiveConfigService,
                                         AgentRunExecutionLeaseService executionLeaseService) {
        this.snapshotMapper = snapshotMapper;
        this.effectiveConfigService = effectiveConfigService;
        this.executionLeaseService = executionLeaseService;
    }

    /**
     * Creates the pre-queue snapshot for a new task. This is a control-plane creation path
     * (the task is not leased yet), so no execution fence is required.
     */
    public AgentRunConfigSnapshot createForNewTask(Integer studentId, StudentProject project,
                                                   Long taskId, Integer modelConfigId, String mode) {
        AgentEffectiveProjectConfigService.EffectiveProjectConfig effective =
                effectiveConfigService.resolve(studentId, project, modelConfigId, mode);
        return insert(taskId, INITIAL_EPOCH, project.getProjectId(), effective);
    }

    /**
     * Copies the latest snapshot into the requested epoch without mutating the prior row.
     * The fence must be active (owner + exact epoch + unexpired lease); stale fences are
     * rejected with the typed lease failure before any write.
     */
    public AgentRunConfigSnapshot copyForNewEpoch(Long taskId, long newEpoch, ExecutionFence fence) {
        requireActiveFence(fence);
        AgentRunConfigSnapshot latest = getLatest(taskId);
        if (latest == null) {
            throw new IllegalStateException("No snapshot exists to copy for task " + taskId);
        }
        // 同 epoch 并发插入在 lease 独占语义下不可达（requireActiveFence 先验 + 唯一键兜底）。
        AgentRunConfigSnapshot copy = new AgentRunConfigSnapshot();
        copy.setTaskId(taskId);
        copy.setExecutionEpoch(newEpoch);
        copy.setProjectId(latest.getProjectId());
        copy.setProjectConfigRevision(latest.getProjectConfigRevision());
        copy.setProjectConfigDigest(latest.getProjectConfigDigest());
        copy.setEffectiveConfigJson(latest.getEffectiveConfigJson());
        copy.setEffectiveConfigDigest(latest.getEffectiveConfigDigest());
        copy.setModelFingerprint(latest.getModelFingerprint());
        copy.setCapabilityDigest(latest.getCapabilityDigest());
        copy.setResourceDigest(latest.getResourceDigest());
        copy.setRuntimeProfile(latest.getRuntimeProfile());
        copy.setNetworkPolicyJson(latest.getNetworkPolicyJson());
        copy.setVerificationPolicyJson(latest.getVerificationPolicyJson());
        copy.setEnvironmentOperationRef(latest.getEnvironmentOperationRef());
        copy.setSecretAliasesJson(latest.getSecretAliasesJson());
        return insert(copy);
    }

    /**
     * Creates a migration snapshot for a legacy active task that has none. Reads ONLY the exact
     * persisted model reference once; a missing reference or an invalid/pending configuration
     * fails closed with a typed configuration failure and no default fallback.
     */
    public AgentRunConfigSnapshot createMigrationSnapshot(AgentTask task, StudentProject project,
                                                          ExecutionFence fence) {
        requireActiveFence(fence);
        if (task.getModelConfigId() == null) {
            throw new AgentRunConfigurationException(
                    AgentRunConfigurationException.Reason.MODEL_CONFIG_NOT_PERSISTED,
                    "task has no persisted model config reference");
        }
        AgentEffectiveProjectConfigService.EffectiveProjectConfig effective =
                effectiveConfigService.resolve(task.getStudentId(), project,
                        task.getModelConfigId(), task.getMode());
        return insert(task.getTaskId(), fence.epoch(), project.getProjectId(), effective);
    }

    /** Returns the snapshot for the exact task/epoch, or null. */
    public AgentRunConfigSnapshot getForEpoch(Long taskId, long executionEpoch) {
        return snapshotMapper.selectOne(new QueryWrapper<AgentRunConfigSnapshot>()
                .eq("task_id", taskId)
                .eq("execution_epoch", executionEpoch)
                .last("LIMIT 1"));
    }

    /** Returns the latest snapshot row for the task (by epoch), or null. */
    public AgentRunConfigSnapshot getLatest(Long taskId) {
        return snapshotMapper.selectOne(new QueryWrapper<AgentRunConfigSnapshot>()
                .eq("task_id", taskId)
                .orderByDesc("execution_epoch")
                .last("LIMIT 1"));
    }

    private AgentRunConfigSnapshot insert(Long taskId, long epoch, Integer projectId,
                                          AgentEffectiveProjectConfigService.EffectiveProjectConfig effective) {
        AgentRunConfigSnapshot snapshot = new AgentRunConfigSnapshot();
        snapshot.setTaskId(taskId);
        snapshot.setExecutionEpoch(epoch);
        snapshot.setProjectId(projectId);
        snapshot.setProjectConfigRevision(effective.projectConfigRevision());
        snapshot.setProjectConfigDigest(effective.projectConfigDigest());
        snapshot.setEffectiveConfigJson(effective.effectiveConfigJson());
        snapshot.setEffectiveConfigDigest(effective.effectiveConfigDigest());
        snapshot.setModelFingerprint(effective.modelFingerprint());
        snapshot.setCapabilityDigest(effective.capabilityDigest());
        snapshot.setResourceDigest(effective.resourceDigest());
        snapshot.setRuntimeProfile(effective.runtimeProfile());
        snapshot.setNetworkPolicyJson(effective.networkPolicyJson());
        snapshot.setVerificationPolicyJson(effective.verificationPolicyJson());
        snapshot.setSecretAliasesJson(effective.secretAliasesJson());
        return insert(snapshot);
    }

    private AgentRunConfigSnapshot insert(AgentRunConfigSnapshot snapshot) {
        LocalDateTime now = LocalDateTime.now();
        snapshot.setCreateTime(now);
        snapshot.setUpdateTime(now);
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    private void requireActiveFence(ExecutionFence fence) {
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for snapshot writes");
        }
        if (executionLeaseService == null) {
            throw new IllegalStateException("Execution lease service is unavailable");
        }
        executionLeaseService.requireActiveFence(fence, LocalDateTime.now());
    }
}
