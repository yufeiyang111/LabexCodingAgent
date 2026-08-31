package com.labex.labexagent.projectconfig;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentRunConfigSnapshot;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunConfigurationException;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.mapper.AgentRunConfigSnapshotMapper;
import com.labex.mapper.AgentTaskMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Durable task-epoch configuration snapshot authority.
 *
 * <p>Each {@code taskId + executionEpoch} owns one configuration snapshot row. The effective
 * configuration fields are immutable after creation; the request-evidence column is a fenced,
 * bounded telemetry projection maintained for prefix/cache diagnostics; finalized iterations are
 * retained once, while same-iteration provider fallbacks replace the provisional entry. New tasks get their
 * snapshot before entering {@code queued} (epoch zero); resume and
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
    private static final Gson GSON = new Gson();

    private final AgentRunConfigSnapshotMapper snapshotMapper;
    private final AgentEffectiveProjectConfigService effectiveConfigService;
    private final AgentRunExecutionLeaseService executionLeaseService;
    private final AgentTaskMapper taskMapper;

    @Autowired
    public AgentRunConfigSnapshotService(AgentRunConfigSnapshotMapper snapshotMapper,
                                         AgentEffectiveProjectConfigService effectiveConfigService,
                                         AgentRunExecutionLeaseService executionLeaseService,
                                         AgentTaskMapper taskMapper) {
        this.snapshotMapper = snapshotMapper;
        this.effectiveConfigService = effectiveConfigService;
        this.executionLeaseService = executionLeaseService;
        this.taskMapper = taskMapper;
    }

    private boolean isKnownPrefixState(String state) {
        return "baseline".equals(state) || "stable".equals(state) || "reset".equals(state)
                || "unavailable".equals(state) || "not_reported".equals(state);
    }

    public AgentRunConfigSnapshotService(AgentRunConfigSnapshotMapper snapshotMapper,
                                         AgentEffectiveProjectConfigService effectiveConfigService,
                                         AgentRunExecutionLeaseService executionLeaseService) {
        this(snapshotMapper, effectiveConfigService, executionLeaseService, null);
    }

    private JsonObject latestEvidenceObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) return null;
            JsonArray entries = parsed.getAsJsonArray();
            for (int index = entries.size() - 1; index >= 0; index--) {
                JsonElement value = entries.get(index);
                if (value != null && value.isJsonObject()) return value.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // 损坏的旧 evidence 只影响可选遥测，不阻断 Agent 请求。
        }
        return null;
    }

    /** 只保留最新请求的 hash 列表，避免长任务 request evidence 变成 O(n^2) 数据。 */
    private void dropHistoricalMessageFingerprints(JsonArray entries) {
        if (entries == null || entries.size() <= 1) return;
        for (int i = 0; i < entries.size() - 1; i++) {
            JsonElement value = entries.get(i);
            if (value != null && value.isJsonObject()) {
                value.getAsJsonObject().remove("messageFingerprints");
            }
        }
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
    @org.springframework.transaction.annotation.Transactional
    public AgentRunConfigSnapshot copyForNewEpoch(Long taskId, long newEpoch, ExecutionFence fence) {
        requireActiveFenceForWrite(fence);
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
    @org.springframework.transaction.annotation.Transactional
    public AgentRunConfigSnapshot createMigrationSnapshot(AgentTask task, StudentProject project,
                                                           ExecutionFence fence) {
        requireActiveFenceForWrite(fence);
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

    @org.springframework.transaction.annotation.Transactional
    public void appendRequestEvidence(ExecutionFence fence, int iteration,
                                      com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.RequestEvidence evidence) {
        if (fence == null || evidence == null) return;
        requireActiveFenceForWrite(fence);
        AgentRunConfigSnapshot snapshot = getForEpoch(fence.taskId(), fence.epoch());
        if (snapshot == null) {
            throw new IllegalStateException("Snapshot does not exist for task " + fence.taskId() + " epoch " + fence.epoch());
        }
        JsonArray entries = new JsonArray();
        if (snapshot.getRequestEvidenceJson() != null && !snapshot.getRequestEvidenceJson().isBlank()) {
            try {
                JsonElement parsed = JsonParser.parseString(snapshot.getRequestEvidenceJson());
                if (parsed.isJsonArray()) {
                    entries = parsed.getAsJsonArray();
                }
            } catch (RuntimeException ignored) {
            }
        }
        JsonObject item = GSON.toJsonTree(evidence).getAsJsonObject();
        item.addProperty("iteration", iteration);
        enrichPrefixEvidence(entries, item);
        replaceOrAppendIterationEvidence(entries, item, iteration);
        dropHistoricalMessageFingerprints(entries);
        snapshot.setRequestEvidenceJson(GSON.toJson(entries));
        snapshotMapper.updateById(snapshot);
    }

    private void replaceOrAppendIterationEvidence(JsonArray entries, JsonObject current, int iteration) {
        if (entries == null || current == null) return;
        for (int i = 0; i < entries.size(); i++) {
            JsonElement el = entries.get(i);
            if (el != null && el.isJsonObject() && el.getAsJsonObject().has("iteration")) {
                int it = integerValue(el.getAsJsonObject().get("iteration"));
                if (it == iteration) {
                    entries.set(i, current);
                    return;
                }
            }
        }
        entries.add(current);
    }

    private void enrichPrefixEvidence(JsonArray entries, JsonObject current) {
        if (entries == null || current == null) return;
        JsonObject previous = lastEvidence(entries);
        if (previous == null) {
            current.addProperty("prefixState", "baseline");
            current.addProperty("prefixStable", (Boolean) null);
            current.addProperty("commonPrefixMessages", 0);
            current.addProperty("commonPrefixShapeChars", 0);
            current.addProperty("inputShapeChars", integerValue(current.get("inputShapeChars")));
            current.addProperty("previousInputShapeChars", 0);
            current.addProperty("prefixResetReason", "");
            return;
        }
        JsonArray prevFps = previous.has("messageFingerprints") && previous.get("messageFingerprints").isJsonArray()
                ? previous.getAsJsonArray("messageFingerprints") : new JsonArray();
        JsonArray currFps = current.has("messageFingerprints") && current.get("messageFingerprints").isJsonArray()
                ? current.getAsJsonArray("messageFingerprints") : new JsonArray();
        int commonMsgs = commonPrefixMessages(prevFps, currFps);
        int commonChars = commonPrefixShapeChars(prevFps, commonMsgs);
        int inputChars = integerValue(current.get("inputShapeChars"));
        int prevInputChars = integerValue(previous.get("inputShapeChars"));
        boolean isStable = commonMsgs == prevFps.size() && prevFps.size() > 0;
        String reason = isStable ? "append_only" : "prefix_diverged";
        current.addProperty("prefixState", isStable ? "stable" : "reset");
        current.addProperty("prefixStable", isStable);
        current.addProperty("commonPrefixMessages", commonMsgs);
        current.addProperty("commonPrefixShapeChars", commonChars);
        current.addProperty("inputShapeChars", inputChars);
        current.addProperty("previousInputShapeChars", prevInputChars);
        current.addProperty("prefixResetReason", reason);
    }

    private JsonObject lastEvidence(JsonArray entries) {
        if (entries == null || entries.isEmpty()) return null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            JsonElement el = entries.get(i);
            if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        }
        return null;
    }

    private int commonPrefixMessages(JsonArray previous, JsonArray current) {
        int common = 0;
        int minSize = Math.min(previous.size(), current.size());
        for (int i = 0; i < minSize; i++) {
            JsonElement p = previous.get(i);
            JsonElement c = current.get(i);
            if (p != null && c != null && p.equals(c)) {
                common++;
            } else {
                break;
            }
        }
        return common;
    }

    private int commonPrefixShapeChars(JsonArray fingerprints, int count) {
        int total = 0;
        for (int i = 0; i < Math.min(count, fingerprints.size()); i++) {
            JsonElement el = fingerprints.get(i);
            if (el != null && el.isJsonObject() && el.getAsJsonObject().has("characterCount")) {
                total += integerValue(el.getAsJsonObject().get("characterCount"));
            }
        }
        return total;
    }

    private static int integerValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return 0;
        try {
            return Math.max(0, value.getAsInt());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * 某个 task 的 OpenAI-compatible 网关是否已经明确拒绝过 prompt_cache_key。
     * 该能力状态跨 execution epoch 读取，但只由同一 task 的脱敏 request evidence 提供。
     */
    public boolean promptCacheKeyRejected(Long taskId, long executionEpoch) {
        if (taskId == null || taskId <= 0) return false;
        java.util.List<AgentRunConfigSnapshot> snapshots = snapshotMapper.selectList(new QueryWrapper<AgentRunConfigSnapshot>()
                .eq("task_id", taskId)
                .le("execution_epoch", executionEpoch)
                .orderByAsc("execution_epoch"));
        for (AgentRunConfigSnapshot snapshot : snapshots == null ? java.util.List.<AgentRunConfigSnapshot>of() : snapshots) {
            if (evidenceContainsPromptCacheRejection(snapshot == null ? null : snapshot.getRequestEvidenceJson())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the latest prefix comparison derived from the durable request evidence for a task
     * epoch. This is a projection only; the snapshot evidence remains the authority.
     */
    public PrefixTelemetry latestPrefixTelemetry(Long taskId, long executionEpoch) {
        if (taskId == null || taskId <= 0) return PrefixTelemetry.notReported();
        AgentRunConfigSnapshot snapshot = getForEpoch(taskId, executionEpoch);
        JsonObject evidence = latestEvidenceObject(snapshot == null ? null : snapshot.getRequestEvidenceJson());
        return PrefixTelemetry.from(evidence);
    }

    /** Returns prefix evidence only when it belongs to the current model iteration. */
    public PrefixTelemetry latestPrefixTelemetry(Long taskId, long executionEpoch, int iteration) {
        if (taskId == null || taskId <= 0) return PrefixTelemetry.notReported();
        AgentRunConfigSnapshot snapshot = getForEpoch(taskId, executionEpoch);
        JsonObject evidence = latestEvidenceObject(snapshot == null ? null : snapshot.getRequestEvidenceJson());
        return PrefixTelemetry.from(evidence, iteration);
    }

    /** Aggregates historical prefix comparisons from snapshot evidence for a conversation. */
    public PrefixAggregate prefixStatsForConversation(String conversationId) {
        return prefixStatsForConversation(conversationId, null, null);
    }

    /** Aggregates historical prefix comparisons for a conversation within its owner scope. */
    public PrefixAggregate prefixStatsForConversation(String conversationId, Integer studentId,
                                                      Integer projectId) {
        if (conversationId == null || conversationId.isBlank() || taskMapper == null) {
            return PrefixAggregate.empty();
        }
        QueryWrapper<AgentTask> query = new QueryWrapper<AgentTask>()
                .eq("conversation_id", conversationId);
        if (studentId != null) query.eq("student_id", studentId);
        if (projectId != null) query.eq("project_id", projectId);
        List<AgentTask> tasks = taskMapper.selectList(query);
        return aggregatePrefixForTasks(taskIds(tasks));
    }

    /** Aggregates historical prefix comparisons from snapshot evidence for one student. */
    public PrefixAggregate prefixStatsForStudent(Integer studentId) {
        if (studentId == null || studentId <= 0 || taskMapper == null) {
            return PrefixAggregate.empty();
        }
        List<AgentTask> tasks = taskMapper.selectList(new QueryWrapper<AgentTask>()
                .eq("student_id", studentId));
        return aggregatePrefixForTasks(taskIds(tasks));
    }

    private List<Long> taskIds(List<AgentTask> tasks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AgentTask task : tasks == null ? List.<AgentTask>of() : tasks) {
            if (task != null && task.getTaskId() != null && task.getTaskId() > 0) {
                ids.add(task.getTaskId());
            }
        }
        return List.copyOf(ids);
    }

    private PrefixAggregate aggregatePrefixForTasks(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return PrefixAggregate.empty();
        List<AgentRunConfigSnapshot> snapshots = snapshotMapper.selectList(new QueryWrapper<AgentRunConfigSnapshot>()
                .in("task_id", taskIds)
                .orderByAsc("task_id")
                .orderByAsc("execution_epoch"));
        int reported = 0;
        int stable = 0;
        int reset = 0;
        String latestState = "not_reported";
        String latestReason = "";
        for (AgentRunConfigSnapshot snapshot : snapshots == null ? List.<AgentRunConfigSnapshot>of() : snapshots) {
            JsonElement parsed = parseEvidence(snapshot == null ? null : snapshot.getRequestEvidenceJson());
            if (parsed == null || !parsed.isJsonArray()) {
                latestState = "unavailable";
                latestReason = "malformed_evidence";
                continue;
            }
            // A newer task/epoch with no comparisons must not inherit the prior task's state.
            latestState = "not_reported";
            latestReason = "";
            for (JsonElement value : parsed.getAsJsonArray()) {
                if (value == null || !value.isJsonObject()) continue;
                JsonObject evidence = value.getAsJsonObject();
                String state = stringValue(evidence.get("prefixState"));
                Boolean isStable = booleanValue(evidence.get("prefixStable"));
                if (isKnownPrefixState(state)) {
                    latestState = state;
                    latestReason = stringValue(evidence.get("prefixResetReason"));
                }
                boolean comparable = isStable != null || "stable".equals(state) || "reset".equals(state);
                if (!comparable) continue;
                reported++;
                if (Boolean.TRUE.equals(isStable) || "stable".equals(state)) {
                    stable++;
                    latestState = "stable";
                } else {
                    reset++;
                    latestState = "reset";
                }
            }
        }
        Double rate = reported == 0 ? null : Math.round(stable * 10000.0 / reported) / 100.0;
        return new PrefixAggregate(latestState, reported, stable, reset, rate, latestReason);
    }

    private JsonElement parseEvidence(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Boolean booleanValue(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringValue(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return value.toString();
        }
    }

    public record PrefixAggregate(String state, int reportedCalls, int stableCalls, int resetCalls,
                                  Double stabilityRate, String resetReason) {
        public PrefixAggregate {
            state = state == null || state.isBlank() ? "not_reported" : state;
            reportedCalls = Math.max(0, reportedCalls);
            stableCalls = Math.max(0, stableCalls);
            resetCalls = Math.max(0, resetCalls);
            resetReason = resetReason == null ? "" : resetReason;
        }

        public static PrefixAggregate empty() {
            return new PrefixAggregate("not_reported", 0, 0, 0, null, "");
        }

        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("prefixState", state);
            payload.put("prefixReportedCalls", reportedCalls);
            payload.put("prefixStableCalls", stableCalls);
            payload.put("prefixResetCalls", resetCalls);
            payload.put("prefixStabilityRate", stabilityRate);
            payload.put("prefixResetReason", resetReason);
            return payload;
        }
    }

    public record PrefixTelemetry(String state, Boolean stable, boolean reported,
                                  int commonPrefixMessages, int commonPrefixShapeChars,
                                  int inputShapeChars, int previousInputShapeChars,
                                  String resetReason) {
        public PrefixTelemetry {
            state = state == null || state.isBlank() ? "not_reported" : state;
            commonPrefixMessages = Math.max(0, commonPrefixMessages);
            commonPrefixShapeChars = Math.max(0, commonPrefixShapeChars);
            inputShapeChars = Math.max(0, inputShapeChars);
            previousInputShapeChars = Math.max(0, previousInputShapeChars);
            resetReason = resetReason == null ? "" : resetReason;
        }

        public static PrefixTelemetry notReported() {
            return new PrefixTelemetry("not_reported", null, false, 0, 0, 0, 0, "");
        }

        public static PrefixTelemetry from(JsonObject evidence) {
            return from(evidence, null);
        }

        public static PrefixTelemetry from(JsonObject evidence, Integer expectedIteration) {
            if (evidence == null) return notReported();
            if (expectedIteration != null && (!evidence.has("iteration")
                    || integerValue(evidence.get("iteration")) != expectedIteration)) {
                return notReported();
            }
            String state = stringValue(evidence.get("prefixState"));
            Boolean stable = booleanValue(evidence.get("prefixStable"));
            boolean reported = stable != null || "stable".equals(state) || "reset".equals(state);
            return new PrefixTelemetry(state, stable, reported,
                    integerValue(evidence.get("commonPrefixMessages")),
                    integerValue(evidence.get("commonPrefixShapeChars")),
                    integerValue(evidence.get("inputShapeChars")),
                    integerValue(evidence.get("previousInputShapeChars")),
                    stringValue(evidence.get("prefixResetReason")));
        }

        public java.util.Map<String, Object> toPayload() {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("prefixState", state);
            payload.put("prefixStabilityReported", reported);
            payload.put("prefixStable", stable);
            payload.put("prefixCommonPrefixMessages", commonPrefixMessages);
            payload.put("prefixCommonPrefixShapeChars", commonPrefixShapeChars);
            payload.put("prefixInputShapeChars", inputShapeChars);
            payload.put("prefixPreviousInputShapeChars", previousInputShapeChars);
            payload.put("prefixResetReason", resetReason);
            return payload;
        }

        private static String stringValue(JsonElement value) {
            if (value == null || value.isJsonNull()) return "";
            try {
                return value.getAsString();
            } catch (RuntimeException ignored) {
                return "";
            }
        }

        private static Boolean booleanValue(JsonElement value) {
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
            try {
                return value.getAsBoolean();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static int integerValue(JsonElement value) {
            if (value == null || value.isJsonNull()) return 0;
            try {
                return Math.max(0, value.getAsInt());
            } catch (RuntimeException ignored) {
                return 0;
            }
        }
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

    private void requireActiveFenceForWrite(ExecutionFence fence) {
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for snapshot writes");
        }
        if (executionLeaseService == null) {
            throw new IllegalStateException("Execution lease service is unavailable");
        }
        executionLeaseService.requireActiveFenceForWrite(fence, LocalDateTime.now());
    }

    private boolean evidenceContainsPromptCacheRejection(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) return false;
            for (JsonElement item : parsed.getAsJsonArray()) {
                if (item != null && item.isJsonObject()
                        && item.getAsJsonObject().has("promptCacheKeyRejected")
                        && item.getAsJsonObject().get("promptCacheKeyRejected").getAsBoolean()) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }
}
