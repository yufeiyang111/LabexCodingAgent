package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentCheckpointStore;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从 AgentTask、Tool Part 与 Run Event 重建执行进度。
 *
 * <p>该服务只产生可重建投影；文件 checkpoint 仅能在没有 durable 工具事实时迁移为
 * RUN_PROGRESS_MIGRATED 事件。</p>
 */
@Service
public class AgentRunProgressProjectionService {
    private static final Gson GSON = new Gson();
    private static final String MIGRATION_EVENT = "RUN_PROGRESS_MIGRATED";
    private static final int MAX_RESULT_CHARS = 12_000;
    private static final int MAX_PROMPT_RESULT_CHARS = 4_000;

    private final AgentRunPartMapper partMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentRunExecutionProgressReducer reducer;

    public AgentRunProgressProjectionService(AgentRunPartMapper partMapper,
                                             AgentRunEventMapper eventMapper,
                                             AgentTaskMapper taskMapper,
                                             AgentRunLifecycleService lifecycleService,
                                             AgentRunExecutionProgressReducer reducer) {
        this.partMapper = partMapper;
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
        this.lifecycleService = lifecycleService;
        this.reducer = reducer;
    }

    public Projection load(Long taskId, long expectedExecutionEpoch) {
        AgentTask task = requireTask(taskId, expectedExecutionEpoch);
        return project(task, toolParts(taskId), relevantEvents(taskId), 0L, "agent_run_part_event");
    }

    /** 数据库优先；只有没有 durable 工具事实和迁移事件时才消费一次旧 checkpoint seed。 */
    @Transactional(rollbackFor = Exception.class)
    public Projection restoreOrMigrate(Long taskId, long expectedExecutionEpoch,
                                       AgentCheckpointStore.LegacyExecutionSeed legacySeed) {
        AgentTask task = requireTask(taskId, expectedExecutionEpoch);
        List<AgentRunPart> parts = toolParts(taskId);
        List<AgentRunEvent> events = relevantEvents(taskId);
        if (hasDurableProgress(parts, events) || legacySeed == null) {
            return project(task, parts, events, 0L, "agent_run_part_event");
        }

        task = lockWritableTask(taskId, expectedExecutionEpoch);
        parts = toolParts(taskId);
        events = relevantEvents(taskId);
        if (hasDurableProgress(parts, events)) {
            return project(task, parts, events, 0L, "agent_run_part_event");
        }

        Map<String, Object> payload = migrationPayload(task, legacySeed);
        AgentRunEvent migrated = lifecycleService.appendEvent(
                taskId, MIGRATION_EVENT, payload, "run-progress-migration:" + taskId);
        if (migrated == null || migrated.getSequenceNumber() == null) {
            throw new IllegalStateException("Unable to persist legacy Agent progress migration");
        }
        return projectionFromSeed(task, legacySeed, migrated.getSequenceNumber());
    }

    private Projection project(AgentTask task, List<AgentRunPart> parts, List<AgentRunEvent> events,
                               long eventSequence, String source) {
        MigrationSeedEvent migration = migrationSeed(events);
        AgentRunExecutionProgressReducer.State state = migration == null
                ? reducer.initial() : migration.state();
        String lastToolCallId = migration == null ? "" : migration.lastToolCallId();
        String lastTool = migration == null ? "" : migration.lastTool();
        String lastStatus = migration == null ? "" : migration.lastStatus();
        String lastResult = migration == null ? "" : migration.lastResult();
        String runLogPath = migration == null ? "" : migration.runLogPath();
        String resumeNote = migration == null ? "" : migration.resumeNote();
        long revision = migration == null ? 0L : migration.sequence();

        List<AgentRunPart> ordered = new ArrayList<>(parts == null ? List.of() : parts);
        ordered.sort(Comparator.comparing(AgentRunPart::getPartId,
                Comparator.nullsLast(Long::compareTo)));
        for (AgentRunPart part : ordered) {
            JsonObject input = parseObject(part.getInputJson());
            state = reducer.apply(state, part.getToolName(), input, part.getStatus(), part.getOutputText());
            lastToolCallId = safe(part.getToolCallId());
            lastTool = safe(part.getToolName());
            lastStatus = safe(part.getStatus());
            lastResult = bounded(part.getOutputText(), MAX_RESULT_CHARS);
            if (part.getPartId() != null) {
                revision = Math.max(revision, part.getPartId());
            }
        }

        for (AgentRunEvent event : events == null ? List.<AgentRunEvent>of() : events) {
            if (event == null || event.getSequenceNumber() == null) continue;
            revision = Math.max(revision, event.getSequenceNumber());
            if ("SESSION".equals(event.getEventType())) {
                String candidate = stringField(event.getPayload(), "logPath");
                if (!candidate.isBlank()) runLogPath = candidate.replace('\\', '/');
            }
        }

        String stage = terminalStage(task, state.stage());
        return new Projection(task.getTaskId(), epoch(task), stage, state.writeCount(), state.verificationCount(),
                state.hasUnverifiedChanges(), state.trustedVerificationSources(), state.unverifiedChangeTargets(),
                lastToolCallId, lastTool, lastStatus, lastResult, runLogPath,
                safe(task.getStatus()), safe(task.getCurrentStep()), safe(task.getSummary()), resumeNote,
                revision, eventSequence, source);
    }

    private Projection projectionFromSeed(AgentTask task, AgentCheckpointStore.LegacyExecutionSeed seed,
                                          long eventSequence) {
        AgentRunExecutionProgressReducer.State state = new AgentRunExecutionProgressReducer.State(
                seed.stage(), seed.writeCount(), seed.verificationCount(), seed.unverifiedChanges(),
                seed.trustedVerificationSources(), seed.unverifiedChangeTargets());
        return new Projection(task.getTaskId(), epoch(task), terminalStage(task, state.stage()),
                state.writeCount(), state.verificationCount(), state.hasUnverifiedChanges(),
                state.trustedVerificationSources(), state.unverifiedChangeTargets(), "",
                seed.lastTool(), "legacy", bounded(seed.lastResult(), MAX_RESULT_CHARS), seed.runLogPath(),
                safe(task.getStatus()), safe(task.getCurrentStep()), safe(task.getSummary()), seed.resumeNote(),
                eventSequence, eventSequence, "legacy_checkpoint_migration");
    }

    private Map<String, Object> migrationPayload(AgentTask task, AgentCheckpointStore.LegacyExecutionSeed seed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("executionEpoch", epoch(task));
        payload.put("authority", "agent_run_part_event");
        payload.put("source", "legacy_checkpoint_migration");
        payload.put("stage", seed.stage());
        payload.put("writeCount", seed.writeCount());
        payload.put("verificationCount", seed.verificationCount());
        payload.put("unverifiedChanges", seed.unverifiedChanges());
        payload.put("trustedVerificationSources", seed.trustedVerificationSources());
        payload.put("unverifiedChangeTargets", seed.unverifiedChangeTargets());
        payload.put("lastTool", seed.lastTool());
        payload.put("lastStatus", "legacy");
        payload.put("lastResult", bounded(seed.lastResult(), MAX_RESULT_CHARS));
        payload.put("runLogPath", seed.runLogPath());
        payload.put("resumeNote", seed.resumeNote());
        return Map.copyOf(payload);
    }

    private boolean hasDurableProgress(List<AgentRunPart> parts, List<AgentRunEvent> events) {
        return (parts != null && !parts.isEmpty()) || migrationSeed(events) != null;
    }

    private MigrationSeedEvent migrationSeed(List<AgentRunEvent> events) {
        if (events == null || events.isEmpty()) return null;
        AgentRunEvent selected = events.stream()
                .filter(event -> event != null && MIGRATION_EVENT.equals(event.getEventType()))
                .max(Comparator.comparing(AgentRunEvent::getSequenceNumber,
                        Comparator.nullsFirst(Long::compareTo)))
                .orElse(null);
        if (selected == null) return null;
        JsonObject payload = parseObject(selected.getPayload());
        AgentRunExecutionProgressReducer.State state = new AgentRunExecutionProgressReducer.State(
                text(payload, "stage", "intake"), integer(payload, "writeCount"),
                integer(payload, "verificationCount"), bool(payload, "unverifiedChanges"),
                stringSet(payload, "trustedVerificationSources", true),
                stringSet(payload, "unverifiedChangeTargets", false));
        return new MigrationSeedEvent(state, text(payload, "lastToolCallId", ""),
                text(payload, "lastTool", ""), text(payload, "lastStatus", "legacy"),
                bounded(text(payload, "lastResult", ""), MAX_RESULT_CHARS),
                text(payload, "runLogPath", ""), text(payload, "resumeNote", ""),
                selected.getSequenceNumber() == null ? 0L : selected.getSequenceNumber());
    }

    private List<AgentRunPart> toolParts(Long taskId) {
        List<AgentRunPart> rows = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getPartType, "tool")
                .orderByAsc(AgentRunPart::getPartId));
        return rows == null ? List.of() : List.copyOf(rows);
    }

    private List<AgentRunEvent> relevantEvents(Long taskId) {
        List<AgentRunEvent> rows = eventMapper.selectList(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId)
                .in(AgentRunEvent::getEventType, List.of("SESSION", MIGRATION_EVENT))
                .orderByAsc(AgentRunEvent::getSequenceNumber));
        return rows == null ? List.of() : List.copyOf(rows);
    }

    private AgentTask requireTask(Long taskId, long expectedExecutionEpoch) {
        if (taskId == null || taskId <= 0L) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Agent task not found: " + taskId);
        }
        validateEpoch(task, expectedExecutionEpoch);
        return task;
    }

    private AgentTask lockWritableTask(Long taskId, long expectedExecutionEpoch) {
        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Agent task not found: " + taskId);
        }
        validateEpoch(task, expectedExecutionEpoch);
        String status = safe(task.getStatus()).toLowerCase(Locale.ROOT);
        if (List.of("completed", "failed", "cancelled").contains(status)) {
            throw new IllegalStateException("Terminal Agent task cannot migrate legacy progress: " + status);
        }
        return task;
    }

    private void validateEpoch(AgentTask task, long expectedExecutionEpoch) {
        long current = epoch(task);
        if (current != expectedExecutionEpoch) {
            throw new StaleProgressEpochException("Progress projection expected epoch " + expectedExecutionEpoch
                    + " but current epoch " + current + " owns task " + task.getTaskId());
        }
    }

    private long epoch(AgentTask task) {
        return task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
    }

    private String terminalStage(AgentTask task, String projectedStage) {
        String status = safe(task.getStatus()).toLowerCase(Locale.ROOT);
        if ("completed".equals(status)) return "final";
        if ("failed".equals(status)) return "repair";
        return projectedStage == null || projectedStage.isBlank() ? "intake" : projectedStage;
    }

    private JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) return new JsonObject();
        try {
            JsonObject value = GSON.fromJson(json, JsonObject.class);
            return value == null ? new JsonObject() : value;
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private String stringField(String json, String key) {
        return text(parseObject(json), key, "");
    }

    private String text(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            String value = object.get(key).getAsString();
            return value == null ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private int integer(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return 0;
        try {
            return Math.max(0, object.get(key).getAsInt());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private boolean bool(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return false;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Set<String> stringSet(JsonObject object, String key, boolean lowerCase) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        object.getAsJsonArray(key).forEach(item -> {
            if (item == null || item.isJsonNull()) return;
            String value = item.getAsString();
            if (value == null || value.isBlank()) return;
            String normalized = value.trim().replace('\\', '/');
            values.add(lowerCase ? normalized.toLowerCase(Locale.ROOT) : normalized);
        });
        return Set.copyOf(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\u0000", "").strip();
    }

    private static String bounded(String value, int maxChars) {
        String normalized = safe(value);
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...truncated...";
    }

    public record Projection(Long taskId,
                             long executionEpoch,
                             String stage,
                             int writeCount,
                             int verificationCount,
                             boolean unverifiedChanges,
                             Set<String> trustedVerificationSources,
                             Set<String> unverifiedChangeTargets,
                             String lastToolCallId,
                             String lastTool,
                             String lastStatus,
                             String lastResult,
                             String runLogPath,
                             String taskStatus,
                             String currentStep,
                             String taskSummary,
                             String resumeNote,
                             long revision,
                             long eventSequence,
                             String source) {
        public Projection {
            trustedVerificationSources = trustedVerificationSources == null
                    ? Set.of() : Set.copyOf(trustedVerificationSources);
            unverifiedChangeTargets = unverifiedChangeTargets == null
                    ? Set.of() : Set.copyOf(unverifiedChangeTargets);
            stage = safe(stage).isBlank() ? "intake" : safe(stage);
            lastToolCallId = safe(lastToolCallId);
            lastTool = safe(lastTool);
            lastStatus = safe(lastStatus);
            lastResult = bounded(lastResult, MAX_RESULT_CHARS);
            runLogPath = safe(runLogPath).replace('\\', '/');
            taskStatus = safe(taskStatus);
            currentStep = safe(currentStep);
            taskSummary = safe(taskSummary);
            resumeNote = safe(resumeNote);
            source = safe(source).isBlank() ? "agent_run_part_event" : safe(source);
        }

        public void applyTo(AgentContext context) {
            if (context == null || !taskId.equals(context.getTaskId())) {
                throw new IllegalArgumentException("Progress projection does not belong to the target Agent context");
            }
            context.applyExecutionProgressProjection(stage, writeCount, verificationCount, unverifiedChanges,
                    trustedVerificationSources, unverifiedChangeTargets);
        }

        public Map<String, Object> eventPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("executionEpoch", executionEpoch);
            payload.put("authority", "agent_run_part_event");
            payload.put("source", source);
            payload.put("stage", stage);
            payload.put("writeCount", writeCount);
            payload.put("verificationCount", verificationCount);
            payload.put("unverifiedChanges", unverifiedChanges);
            payload.put("trustedVerificationSources", trustedVerificationSources);
            payload.put("unverifiedChangeTargets", unverifiedChangeTargets);
            payload.put("lastToolCallId", lastToolCallId);
            payload.put("lastTool", lastTool);
            payload.put("lastStatus", lastStatus);
            payload.put("lastResult", lastResult);
            payload.put("runLogPath", runLogPath);
            payload.put("resumeNote", resumeNote);
            payload.put("projectionRevision", revision);
            return Map.copyOf(payload);
        }

        public String renderForPrompt() {
            return """
                    <agent_run_progress version="1">
                    authority: agent_run_part_event
                    source: %s
                    task_id: %s
                    execution_epoch: %d
                    task_status: %s
                    current_step_json: %s
                    task_summary_json: %s
                    stage: %s
                    write_count: %d
                    verification_count: %d
                    unverified_changes: %s
                    trusted_verification_sources_json: %s
                    unverified_change_targets_json: %s
                    last_tool_call_id: %s
                    last_tool: %s
                    last_tool_status: %s
                    last_result_json: %s
                    run_log_path_json: %s
                    resume_note_json: %s
                    projection_revision: %d
                    </agent_run_progress>
                    """.formatted(source, taskId, executionEpoch, taskStatus,
                    GSON.toJson(currentStep), GSON.toJson(taskSummary), stage, writeCount, verificationCount,
                    unverifiedChanges, GSON.toJson(trustedVerificationSources), GSON.toJson(unverifiedChangeTargets),
                    lastToolCallId, lastTool, lastStatus, GSON.toJson(bounded(lastResult, MAX_PROMPT_RESULT_CHARS)),
                    GSON.toJson(runLogPath), GSON.toJson(resumeNote), revision);
        }
    }

    private record MigrationSeedEvent(AgentRunExecutionProgressReducer.State state,
                                      String lastToolCallId,
                                      String lastTool,
                                      String lastStatus,
                                      String lastResult,
                                      String runLogPath,
                                      String resumeNote,
                                      long sequence) {
    }

    public static final class StaleProgressEpochException extends IllegalStateException {
        public StaleProgressEpochException(String message) {
            super(message);
        }
    }
}