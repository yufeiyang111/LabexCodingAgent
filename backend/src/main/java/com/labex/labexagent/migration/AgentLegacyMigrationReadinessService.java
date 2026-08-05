package com.labex.labexagent.migration;

import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentCheckpointStore;
import com.labex.labexagent.service.AgentLegacyConversationHistoryMigrationService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.StudentProjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 汇总旧版 reader 的迁移存量和安全删除条件，供管理员只读 API 使用。 */
@Service
public class AgentLegacyMigrationReadinessService {
    private static final int TASK_LOOKUP_BATCH_SIZE = 500;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final StudentProjectMapper projectMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentCheckpointStore checkpointStore;
    private final AgentLegacyMigrationGateService gateService;
    private final Clock clock;

    @Autowired
    public AgentLegacyMigrationReadinessService(AgentConversationMapper conversationMapper,
                                                AgentMessageMapper messageMapper,
                                                StudentProjectMapper projectMapper,
                                                AgentRunEventMapper eventMapper,
                                                AgentTaskMapper taskMapper,
                                                AgentCheckpointStore checkpointStore,
                                                AgentLegacyMigrationGateService gateService) {
        this(conversationMapper, messageMapper, projectMapper, eventMapper, taskMapper,
                checkpointStore, gateService, Clock.systemDefaultZone());
    }

    AgentLegacyMigrationReadinessService(AgentConversationMapper conversationMapper,
                                         AgentMessageMapper messageMapper,
                                         StudentProjectMapper projectMapper,
                                         AgentRunEventMapper eventMapper,
                                         AgentTaskMapper taskMapper,
                                         AgentCheckpointStore checkpointStore,
                                         AgentLegacyMigrationGateService gateService,
                                         Clock clock) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper, "conversationMapper");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.projectMapper = Objects.requireNonNull(projectMapper, "projectMapper");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.gateService = Objects.requireNonNull(gateService, "gateService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReadinessReport refresh() {
        long totalHistory = safe(conversationMapper.countActiveHistorySources());
        long pendingHistory = safe(conversationMapper.countPendingHistorySources(
                AgentLegacyConversationHistoryMigrationService.DURABLE_VERSION));
        long retainedLegacyRows = safe(messageMapper.countRetainedLegacyRows());
        AgentLegacyMigrationGateService.GateSnapshot historyGate = gateService.refreshInventory(
                AgentLegacyMigrationGateService.LEGACY_HISTORY_READER, pendingHistory);
        ReaderReadiness history = new ReaderReadiness(
                AgentLegacyMigrationGateService.LEGACY_HISTORY_READER,
                totalHistory, pendingHistory, Math.max(0L, totalHistory - pendingHistory),
                0L, 0L, 0L, retainedLegacyRows, false, historyGate);

        List<StudentProject> projects = projectMapper.selectActiveProjects();
        AgentCheckpointStore.LegacyInventory checkpointInventory = checkpointStore.scanLegacySources(
                projects == null ? List.of() : projects);
        Set<Long> inspectedTaskIds = inspectedTaskIds();
        Map<Long, AgentTask> tasksById = loadTasks(checkpointInventory.sources());

        long coveredCheckpointSources = 0L;
        long uninspectedCheckpointSources = 0L;
        long unownedCheckpointSources = 0L;
        for (AgentCheckpointStore.LegacySource source : checkpointInventory.sources()) {
            AgentTask task = source == null ? null : tasksById.get(source.taskId());
            if (!matchesTaskBoundary(source, task)) {
                unownedCheckpointSources++;
            } else if (inspectedTaskIds.contains(source.taskId())) {
                coveredCheckpointSources++;
            } else {
                uninspectedCheckpointSources++;
            }
        }

        long truncationBlocker = checkpointInventory.truncated() ? 1L : 0L;
        long pendingCheckpointSources = uninspectedCheckpointSources
                + unownedCheckpointSources
                + checkpointInventory.invalidSources()
                + checkpointInventory.unscannableProjects()
                + truncationBlocker;
        long totalCheckpointSources = checkpointInventory.sources().size()
                + checkpointInventory.invalidSources()
                + checkpointInventory.unscannableProjects()
                + truncationBlocker;
        AgentLegacyMigrationGateService.GateSnapshot checkpointGate = gateService.refreshInventory(
                AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER, pendingCheckpointSources);
        ReaderReadiness checkpoint = new ReaderReadiness(
                AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER,
                totalCheckpointSources, pendingCheckpointSources, coveredCheckpointSources,
                checkpointInventory.invalidSources(), unownedCheckpointSources,
                checkpointInventory.unscannableProjects(), checkpointInventory.sources().size(),
                checkpointInventory.truncated(), checkpointGate);

        List<ReaderReadiness> readers = List.of(history, checkpoint);
        return new ReadinessReport(LocalDateTime.now(clock),
                readers.stream().allMatch(ReaderReadiness::readyForRemoval), readers);
    }

    private Set<Long> inspectedTaskIds() {
        List<Long> rows = eventMapper.selectDistinctTaskIdsByEventType(
                AgentLegacyCheckpointMigrationService.INSPECTION_EVENT);
        return new LinkedHashSet<>(rows == null ? List.of() : rows);
    }

    private Map<Long, AgentTask> loadTasks(List<AgentCheckpointStore.LegacySource> sources) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (sources != null) {
            for (AgentCheckpointStore.LegacySource source : sources) {
                if (source != null && source.taskId() != null && source.taskId() > 0L) {
                    ids.add(source.taskId());
                }
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }

        List<Long> orderedIds = List.copyOf(ids);
        Map<Long, AgentTask> result = new LinkedHashMap<>();
        for (int offset = 0; offset < orderedIds.size(); offset += TASK_LOOKUP_BATCH_SIZE) {
            int end = Math.min(orderedIds.size(), offset + TASK_LOOKUP_BATCH_SIZE);
            List<AgentTask> rows = taskMapper.selectBatchIds(orderedIds.subList(offset, end));
            if (rows == null) {
                continue;
            }
            for (AgentTask task : rows) {
                if (task != null && task.getTaskId() != null) {
                    result.put(task.getTaskId(), task);
                }
            }
        }
        return Map.copyOf(result);
    }

    private boolean matchesTaskBoundary(AgentCheckpointStore.LegacySource source, AgentTask task) {
        return source != null && task != null
                && Objects.equals(source.taskId(), task.getTaskId())
                && Objects.equals(source.studentId(), task.getStudentId())
                && Objects.equals(source.projectId(), task.getProjectId())
                && Objects.equals(source.conversationId(), task.getConversationId());
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    public record ReadinessReport(LocalDateTime generatedAt,
                                  boolean readyForRemoval,
                                  List<ReaderReadiness> readers) {
        public ReadinessReport {
            readers = readers == null ? List.of() : List.copyOf(readers);
        }

        public ReaderReadiness reader(String readerKey) {
            return readers.stream()
                    .filter(reader -> reader.readerKey().equals(readerKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown reader in report: " + readerKey));
        }
    }

    public record ReaderReadiness(String readerKey,
                                  long totalSources,
                                  long pendingSources,
                                  long coveredSources,
                                  long invalidSources,
                                  long unownedSources,
                                  long unscannableProjects,
                                  long retainedItems,
                                  boolean inventoryTruncated,
                                  AgentLegacyMigrationGateService.GateSnapshot gate) {
        public boolean readyForRemoval() {
            return gate != null && gate.readyForRemoval()
                    && pendingSources == 0L && !inventoryTruncated;
        }
    }
}
