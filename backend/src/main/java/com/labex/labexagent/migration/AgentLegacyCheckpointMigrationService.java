package com.labex.labexagent.migration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunPlanService;
import com.labex.labexagent.run.AgentRunProgressProjectionService;
import com.labex.labexagent.runtime.AgentCheckpointStore;
import com.labex.mapper.AgentRunEventMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将 v1/v2 checkpoint 一次性迁移到 durable 进度、计划和检查事件。 */
@Service
public class AgentLegacyCheckpointMigrationService {
    public static final String INSPECTION_EVENT = "LEGACY_CHECKPOINT_INSPECTED";

    private final AgentCheckpointStore checkpointStore;
    private final AgentRunProgressProjectionService progressService;
    private final AgentRunPlanService planService;
    private final AgentRunEventMapper eventMapper;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentLegacyMigrationGateService gateService;

    public AgentLegacyCheckpointMigrationService(AgentCheckpointStore checkpointStore,
                                                  AgentRunProgressProjectionService progressService,
                                                  AgentRunPlanService planService,
                                                  AgentRunEventMapper eventMapper,
                                                  AgentRunLifecycleService lifecycleService,
                                                  AgentLegacyMigrationGateService gateService) {
        this.checkpointStore = checkpointStore;
        this.progressService = progressService;
        this.planService = planService;
        this.eventMapper = eventMapper;
        this.lifecycleService = lifecycleService;
        this.gateService = gateService;
    }

    @Transactional(rollbackFor = Exception.class)
    public RestoreResult restoreOrMigrate(StudentProject project,
                                          String conversationId,
                                          AgentTask task,
                                          long executionEpoch,
                                          boolean resumedRun) {
        if (task == null || task.getTaskId() == null) {
            throw new IllegalArgumentException("Agent task is required for legacy checkpoint migration");
        }
        Long taskId = task.getTaskId();
        boolean inspectNow = resumedRun && !alreadyInspected(taskId);
        Optional<AgentCheckpointStore.Snapshot> checkpoint = inspectNow
                ? checkpointStore.loadLegacy(project, conversationId, taskId)
                : Optional.empty();
        checkpoint.ifPresent(snapshot -> gateService.recordReaderHit(
                AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER, 1L));

        AgentCheckpointStore.LegacyExecutionSeed executionSeed = checkpoint
                .flatMap(AgentCheckpointStore.Snapshot::legacyExecutionSeed)
                .orElse(null);
        List<AgentRunPlanService.PlanDraft> planDrafts = checkpoint
                .flatMap(AgentCheckpointStore.Snapshot::legacyPlanSeed)
                .map(seed -> seed.items().stream()
                        .map(item -> new AgentRunPlanService.PlanDraft(
                                item.getTitle(), item.getDescription(), item.isCompleted()))
                        .toList())
                .orElse(List.of());

        AgentRunProgressProjectionService.Projection progress = progressService.restoreOrMigrate(
                taskId, executionEpoch, executionSeed);
        AgentRunPlanService.Projection plan = planService.restoreOrMigrate(
                taskId, executionEpoch, planDrafts);
        long inspectionSequence = inspectNow
                ? persistInspection(taskId, executionEpoch, checkpoint, executionSeed != null,
                        !planDrafts.isEmpty(), progress, plan)
                : 0L;
        return new RestoreResult(progress, plan, inspectionSequence);
    }

    private boolean alreadyInspected(Long taskId) {
        Long count = eventMapper.selectCount(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId)
                .eq(AgentRunEvent::getEventType, INSPECTION_EVENT));
        return count != null && count > 0L;
    }

    private long persistInspection(Long taskId,
                                   long executionEpoch,
                                   Optional<AgentCheckpointStore.Snapshot> checkpoint,
                                   boolean executionSeedPresent,
                                   boolean planSeedPresent,
                                   AgentRunProgressProjectionService.Projection progress,
                                   AgentRunPlanService.Projection plan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("executionEpoch", executionEpoch);
        payload.put("authority", "agent_run_event");
        payload.put("readerKey", AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER);
        payload.put("sourcePresent", checkpoint.isPresent());
        payload.put("sourceVersion", checkpoint.map(AgentCheckpointStore.Snapshot::version).orElse(0));
        payload.put("executionSeedPresent", executionSeedPresent);
        payload.put("planSeedPresent", planSeedPresent);
        payload.put("outcome", outcome(checkpoint.isPresent(), executionSeedPresent, planSeedPresent, progress, plan));
        AgentRunEvent event = lifecycleService.appendEvent(
                taskId, INSPECTION_EVENT, Map.copyOf(payload), "legacy-checkpoint-inspected:" + taskId);
        if (event == null || event.getSequenceNumber() == null) {
            throw new IllegalStateException("Unable to persist legacy checkpoint inspection marker");
        }
        return event.getSequenceNumber();
    }

    private String outcome(boolean sourcePresent,
                           boolean executionSeedPresent,
                           boolean planSeedPresent,
                           AgentRunProgressProjectionService.Projection progress,
                           AgentRunPlanService.Projection plan) {
        if (!sourcePresent) {
            return "absent";
        }
        if (!executionSeedPresent && !planSeedPresent) {
            return "empty";
        }
        if ((progress != null && progress.eventSequence() > 0L)
                || (plan != null && plan.eventSequence() > 0L)) {
            return "migrated";
        }
        return "durable_covered";
    }

    public record RestoreResult(AgentRunProgressProjectionService.Projection progressProjection,
                                AgentRunPlanService.Projection planProjection,
                                long inspectionEventSequence) {
    }
}
