package com.labex.labexagent.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.labex.labexagent.runtime.AgentProviderMessageProjector;
import com.labex.entity.AgentConversation;
import com.labex.mapper.AgentCompactionRecordMapper;
import com.labex.mapper.AgentConversationMapper;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化 compaction epoch，并从最新完成记录重建 Provider 投影。 */
@Service
public class AgentCompactionService {
    public static final String SCOPE_TASK = "task";
    public static final String SCOPE_CONVERSATION = "conversation";
    private static final Gson GSON = new Gson();
    private static final Type MESSAGE_LIST = new TypeToken<List<Map<String, Object>>>() { }.getType();

    private final AgentCompactionRecordMapper mapper;
    private final AgentConversationMapper conversationMapper;
    private final AgentProviderMessageProjector projector = new AgentProviderMessageProjector();

    public AgentCompactionService(AgentCompactionRecordMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public AgentCompactionService(AgentCompactionRecordMapper mapper, AgentConversationMapper conversationMapper) {
        this.mapper = mapper;
        this.conversationMapper = conversationMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentCompactionRecord start(StartRequest request) {
        if (request == null || request.taskId() == null || request.taskId() <= 0) {
            throw new IllegalArgumentException("Compaction task id is required");
        }
        if (request.selection() == null || !request.selection().changed()) {
            throw new IllegalArgumentException("Compaction requires a non-empty head and retained tail");
        }
        AgentCompactionRecord latest = latest(request.taskId(), null);
        long nextEpoch = latest == null || latest.getCompactionEpoch() == null
                ? 1L : latest.getCompactionEpoch() + 1L;
        AgentCompactionRecord record = new AgentCompactionRecord();
        record.setTaskId(request.taskId());
        record.setScope(SCOPE_TASK);
        record.setConversationId(request.conversationId());
        record.setStudentId(request.studentId());
        record.setProjectId(request.projectId());
        record.setExecutionEpoch(Math.max(0L, request.executionEpoch()));
        record.setCompactionEpoch(nextEpoch);
        record.setTriggerReason(safe(request.triggerReason()));
        record.setStatus("running");
        record.setPreviousSummary(safe(request.previousSummary()));
        record.setCompactedHead(GSON.toJson(request.selection().compactedHead()));
        record.setRetainedTail(GSON.toJson(request.selection().retainedTail()));
        record.setTailStartIndex(request.selection().tailStartIndex());
        record.setRetainedTurns(request.selection().retainedTurns());
        record.setSourceMaxSequence(Math.max(-1L, request.sourceMaxSequence()));
        record.setSourceMaxTaskId(null);
        record.setEstimatedTokensBefore(Math.max(0, request.estimatedTokensBefore()));
        record.setModelWindowTokens(Math.max(0, request.modelWindowTokens()));
        record.setReservedOutputTokens(Math.max(0, request.reservedOutputTokens()));
        if (mapper.insert(record) != 1 || record.getCompactionId() == null) {
            throw new IllegalStateException("Unable to persist compaction start record");
        }
        return record;
    }

    /** 为跨任务会话历史创建独立的持久化压缩 epoch。 */
    @Transactional(rollbackFor = Exception.class)
    public AgentCompactionRecord startConversation(ConversationStartRequest request) {
        if (request == null || request.taskId() == null || request.taskId() <= 0) {
            throw new IllegalArgumentException("Conversation compaction task id is required");
        }
        if (request.conversationId() == null || request.conversationId().isBlank()) {
            throw new IllegalArgumentException("Conversation compaction conversation id is required");
        }
        if (request.studentId() == null || request.projectId() == null) {
            throw new IllegalArgumentException("Conversation compaction ownership is required");
        }
        if (request.selection() == null || !request.selection().changed()) {
            throw new IllegalArgumentException("Compaction requires a non-empty head and retained tail");
        }
        if (conversationMapper == null) {
            throw new IllegalStateException("Conversation compaction lock mapper is unavailable");
        }
        AgentConversation locked = conversationMapper.selectOwnedForUpdate(
                request.studentId(), request.projectId(), request.conversationId());
        if (locked == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        AgentCompactionRecord latest = latestConversation(
                request.studentId(), request.projectId(), request.conversationId(), null);
        if (latest != null && "running".equalsIgnoreCase(latest.getStatus())) {
            throw new IllegalStateException("Conversation compaction is already running");
        }
        AgentCompactionRecord latestCompleted = latest != null && "completed".equalsIgnoreCase(latest.getStatus())
                ? latest
                : latestConversation(request.studentId(), request.projectId(), request.conversationId(), "completed");
        Long actualPreviousId = latestCompleted == null ? null : latestCompleted.getCompactionId();
        if (!Objects.equals(request.expectedPreviousCompactionId(), actualPreviousId)) {
            throw new IllegalStateException("Conversation compaction snapshot is stale");
        }
        long nextEpoch = latest == null || latest.getCompactionEpoch() == null
                ? 1L : latest.getCompactionEpoch() + 1L;
        AgentCompactionRecord record = new AgentCompactionRecord();
        record.setTaskId(request.taskId());
        record.setScope(SCOPE_CONVERSATION);
        record.setConversationId(request.conversationId());
        record.setStudentId(request.studentId());
        record.setProjectId(request.projectId());
        record.setExecutionEpoch(Math.max(0L, request.executionEpoch()));
        record.setCompactionEpoch(nextEpoch);
        record.setTriggerReason(safe(request.triggerReason()));
        record.setStatus("running");
        record.setPreviousSummary(safe(request.previousSummary()));
        record.setCompactedHead(GSON.toJson(request.selection().compactedHead()));
        record.setRetainedTail(GSON.toJson(request.selection().retainedTail()));
        record.setTailStartIndex(request.selection().tailStartIndex());
        record.setRetainedTurns(request.selection().retainedTurns());
        record.setSourceMaxSequence(-1L);
        record.setSourceMaxTaskId(Math.max(0L, request.sourceMaxTaskId()));
        record.setEstimatedTokensBefore(Math.max(0, request.estimatedTokensBefore()));
        record.setModelWindowTokens(Math.max(0, request.modelWindowTokens()));
        record.setReservedOutputTokens(Math.max(0, request.reservedOutputTokens()));
        if (mapper.insert(record) != 1 || record.getCompactionId() == null) {
            throw new IllegalStateException("Unable to persist conversation compaction start record");
        }
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(AgentCompactionRecord record, String summary, int estimatedTokensAfter) {
        requirePersisted(record);
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Completed compaction summary is required");
        }
        int normalizedTokens = Math.max(0, estimatedTokensAfter);
        LocalDateTime now = LocalDateTime.now();
        int updated = mapper.update(null, finalizationUpdate(record)
                .set("summary", summary)
                .set("estimated_tokens_after", normalizedTokens)
                .set("failure_reason", null)
                .set("status", "completed")
                .set("update_time", now));
        if (updated == 1) {
            applyCompleted(record, summary, normalizedTokens, now);
            return;
        }
        AgentCompactionRecord persisted = mapper.selectById(record.getCompactionId());
        if (sameCompletedResult(persisted, record, summary, normalizedTokens)) {
            applyCompleted(record, summary, normalizedTokens, persisted.getUpdateTime());
            return;
        }
        throw new IllegalStateException("Unable to persist completed compaction state");
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(AgentCompactionRecord record, String failureReason) {
        requirePersisted(record);
        String normalizedReason = safe(failureReason);
        LocalDateTime now = LocalDateTime.now();
        int updated = mapper.update(null, finalizationUpdate(record)
                .set("failure_reason", normalizedReason)
                .set("status", "failed")
                .set("update_time", now));
        if (updated == 1) {
            applyFailed(record, normalizedReason, now);
            return;
        }
        AgentCompactionRecord persisted = mapper.selectById(record.getCompactionId());
        if (sameFailedResult(persisted, record, normalizedReason)) {
            applyFailed(record, normalizedReason, persisted.getUpdateTime());
            return;
        }
        throw new IllegalStateException("Unable to persist failed compaction state");
    }

    /** 启动恢复按主键游标分页读取 running epoch，避免无界加载和重复扫描有效 lease。 */
    public List<AgentCompactionRecord> runningRecordsAfter(long compactionIdExclusive, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<AgentCompactionRecord> records = mapper.selectList(new LambdaQueryWrapper<AgentCompactionRecord>()
                .eq(AgentCompactionRecord::getStatus, "running")
                .gt(AgentCompactionRecord::getCompactionId, Math.max(0L, compactionIdExclusive))
                .orderByAsc(AgentCompactionRecord::getCompactionId)
                .last("LIMIT " + boundedLimit));
        return records == null ? List.of() : records;
    }

    private UpdateWrapper<AgentCompactionRecord> finalizationUpdate(AgentCompactionRecord record) {
        UpdateWrapper<AgentCompactionRecord> update = new UpdateWrapper<AgentCompactionRecord>()
                .eq("compaction_id", record.getCompactionId())
                .eq("task_id", record.getTaskId())
                .eq("compaction_epoch", record.getCompactionEpoch())
                .eq("status", "running");
        if (record.getScope() != null && !record.getScope().isBlank()) {
            update.eq("scope", record.getScope());
        }
        return update;
    }

    private boolean sameCompletedResult(AgentCompactionRecord persisted, AgentCompactionRecord requested,
                                        String summary, int estimatedTokensAfter) {
        return sameIdentity(persisted, requested)
                && "completed".equalsIgnoreCase(persisted.getStatus())
                && Objects.equals(summary, persisted.getSummary())
                && Objects.equals(estimatedTokensAfter, persisted.getEstimatedTokensAfter());
    }

    private boolean sameFailedResult(AgentCompactionRecord persisted, AgentCompactionRecord requested,
                                     String failureReason) {
        return sameIdentity(persisted, requested)
                && "failed".equalsIgnoreCase(persisted.getStatus())
                && Objects.equals(failureReason, safe(persisted.getFailureReason()));
    }

    private boolean sameIdentity(AgentCompactionRecord persisted, AgentCompactionRecord requested) {
        return persisted != null
                && Objects.equals(persisted.getCompactionId(), requested.getCompactionId())
                && Objects.equals(persisted.getTaskId(), requested.getTaskId())
                && Objects.equals(persisted.getCompactionEpoch(), requested.getCompactionEpoch())
                && Objects.equals(scopeOf(persisted), scopeOf(requested));
    }

    private void applyCompleted(AgentCompactionRecord record, String summary, int estimatedTokensAfter,
                                LocalDateTime updateTime) {
        record.setSummary(summary);
        record.setEstimatedTokensAfter(estimatedTokensAfter);
        record.setFailureReason(null);
        record.setStatus("completed");
        record.setUpdateTime(updateTime == null ? LocalDateTime.now() : updateTime);
    }

    private void applyFailed(AgentCompactionRecord record, String failureReason, LocalDateTime updateTime) {
        record.setFailureReason(failureReason);
        record.setStatus("failed");
        record.setUpdateTime(updateTime == null ? LocalDateTime.now() : updateTime);
    }
    /** 返回任务压缩记录的安全审计投影，不暴露摘要和原始 transcript。 */
    public List<Map<String, Object>> publicHistory(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return List.of();
        }
        LambdaQueryWrapper<AgentCompactionRecord> query = new LambdaQueryWrapper<AgentCompactionRecord>()
                .eq(AgentCompactionRecord::getTaskId, taskId)
                .orderByAsc(AgentCompactionRecord::getCompactionEpoch);
        List<AgentCompactionRecord> records = mapper.selectList(query);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream().map(this::publicRecord).toList();
    }

    public Optional<AgentCompactionRecord> latestCompleted(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(latest(taskId, "completed"));
    }

    public String previousSummary(Long taskId) {
        return latestCompleted(taskId).map(AgentCompactionRecord::getSummary).orElse("");
    }

    public Optional<AgentCompactionRecord> latestCompletedConversation(Integer studentId, Integer projectId,
                                                                        String conversationId) {
        if (studentId == null || projectId == null || conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestConversation(studentId, projectId, conversationId, "completed"));
    }

    public String previousConversationSummary(Integer studentId, Integer projectId, String conversationId) {
        return latestCompletedConversation(studentId, projectId, conversationId)
                .map(AgentCompactionRecord::getSummary).orElse("");
    }

    /** 从指定的已完成会话压缩记录与边界后的新任务重建 Provider 投影。 */
    public ConversationProjection projectConversation(AgentCompactionRecord record,
                                                      List<Map<String, Object>> appendedMessages) {
        if (record == null || !SCOPE_CONVERSATION.equalsIgnoreCase(scopeOf(record))
                || !"completed".equalsIgnoreCase(record.getStatus())
                || record.getSummary() == null || record.getSummary().isBlank()) {
            throw new IllegalArgumentException("Completed conversation compaction record is required");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", record.getSummary()));
        messages.addAll(parseMessages(record.getRetainedTail(), true));
        if (appendedMessages != null) {
            messages.addAll(appendedMessages);
        }
        return new ConversationProjection(projector.project(messages),
                record.getCompactionEpoch() == null ? 0L : record.getCompactionEpoch(),
                record.getSourceMaxTaskId() == null ? 0L : record.getSourceMaxTaskId());
    }

    public Optional<Projection> projectLatest(Long taskId,
                                              LongFunction<List<Map<String, Object>>> appendedMessageLoader) {
        return projectLatestInternal(taskId, appendedMessageLoader, true);
    }

    /** 交互恢复的中间状态不进行严格校验，补写 role=tool 后再校验。 */
    public Optional<Projection> projectLatestForInteractionResume(
            Long taskId, LongFunction<List<Map<String, Object>>> appendedMessageLoader) {
        return projectLatestInternal(taskId, appendedMessageLoader, false);
    }

    private Optional<Projection> projectLatestInternal(Long taskId,
                                                       LongFunction<List<Map<String, Object>>> appendedMessageLoader,
                                                       boolean validate) {
        AgentCompactionRecord record = latestCompleted(taskId).orElse(null);
        if (record == null || record.getSummary() == null || record.getSummary().isBlank()) {
            return Optional.empty();
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", record.getSummary()));
        messages.addAll(parseMessages(record.getRetainedTail(), validate));
        if (appendedMessageLoader != null) {
            List<Map<String, Object>> appended = appendedMessageLoader.apply(
                    record.getSourceMaxSequence() == null ? -1L : record.getSourceMaxSequence());
            if (appended != null) {
                messages.addAll(appended);
            }
        }
        List<Map<String, Object>> projected = validate
                ? projector.project(messages)
                : projector.copyMessages(messages);
        return Optional.of(new Projection(projected,
                record.getCompactionEpoch() == null ? 0L : record.getCompactionEpoch(),
                record.getSourceMaxSequence() == null ? -1L : record.getSourceMaxSequence()));
    }

    private Map<String, Object> publicRecord(AgentCompactionRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("compactionId", record.getCompactionId());
        result.put("scope", scopeOf(record));
        result.put("executionEpoch", record.getExecutionEpoch());
        result.put("compactionEpoch", record.getCompactionEpoch());
        result.put("triggerReason", record.getTriggerReason());
        result.put("status", record.getStatus());
        result.put("retainedTurns", record.getRetainedTurns());
        result.put("sourceMaxSequence", record.getSourceMaxSequence());
        result.put("sourceMaxTaskId", record.getSourceMaxTaskId());
        result.put("estimatedTokensBefore", record.getEstimatedTokensBefore());
        result.put("estimatedTokensAfter", record.getEstimatedTokensAfter());
        result.put("modelWindowTokens", record.getModelWindowTokens());
        result.put("reservedOutputTokens", record.getReservedOutputTokens());
        result.put("failureReason", record.getFailureReason());
        result.put("createTime", record.getCreateTime());
        result.put("updateTime", record.getUpdateTime());
        return result;
    }

    private AgentCompactionRecord latest(Long taskId, String status) {
        LambdaQueryWrapper<AgentCompactionRecord> query = new LambdaQueryWrapper<AgentCompactionRecord>()
                .eq(AgentCompactionRecord::getTaskId, taskId)
                .eq(AgentCompactionRecord::getScope, SCOPE_TASK);
        if (status != null) {
            query.eq(AgentCompactionRecord::getStatus, status);
        }
        query.orderByDesc(AgentCompactionRecord::getCompactionEpoch).last("LIMIT 1");
        return mapper.selectOne(query);
    }

    private AgentCompactionRecord latestConversation(Integer studentId, Integer projectId,
                                                       String conversationId, String status) {
        LambdaQueryWrapper<AgentCompactionRecord> query = new LambdaQueryWrapper<AgentCompactionRecord>()
                .eq(AgentCompactionRecord::getStudentId, studentId)
                .eq(AgentCompactionRecord::getProjectId, projectId)
                .eq(AgentCompactionRecord::getConversationId, conversationId)
                .eq(AgentCompactionRecord::getScope, SCOPE_CONVERSATION);
        if (status != null) {
            query.eq(AgentCompactionRecord::getStatus, status);
        }
        query.orderByDesc(AgentCompactionRecord::getCompactionEpoch).last("LIMIT 1");
        return mapper.selectOne(query);
    }

    private List<Map<String, Object>> parseMessages(String json, boolean validate) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> parsed = GSON.fromJson(json, MESSAGE_LIST);
        List<Map<String, Object>> messages = parsed == null ? List.of() : parsed;
        return validate ? projector.project(messages) : projector.copyMessages(messages);
    }

    private void requirePersisted(AgentCompactionRecord record) {
        if (record == null || record.getCompactionId() == null
                || record.getTaskId() == null || record.getCompactionEpoch() == null) {
            throw new IllegalArgumentException("Persisted compaction identity is required");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String scopeOf(AgentCompactionRecord record) {
        return record == null || record.getScope() == null || record.getScope().isBlank()
                ? SCOPE_TASK : record.getScope();
    }

    public record StartRequest(Long taskId,
                               String conversationId,
                               Integer studentId,
                               Integer projectId,
                               long executionEpoch,
                               String triggerReason,
                               String previousSummary,
                               CompactionSelection selection,
                               long sourceMaxSequence,
                               int estimatedTokensBefore,
                               int modelWindowTokens,
                               int reservedOutputTokens) {
    }

    public record ConversationStartRequest(Long taskId,
                                           String conversationId,
                                           Integer studentId,
                                           Integer projectId,
                                           long executionEpoch,
                                           String triggerReason,
                                           Long expectedPreviousCompactionId,
                                           String previousSummary,
                                           CompactionSelection selection,
                                           long sourceMaxTaskId,
                                           int estimatedTokensBefore,
                                           int modelWindowTokens,
                                           int reservedOutputTokens) {
    }

    public record ConversationProjection(List<Map<String, Object>> messages,
                                         long compactionEpoch,
                                         long sourceMaxTaskId) {
        public ConversationProjection {
            messages = List.copyOf(messages == null ? List.of() : messages);
            sourceMaxTaskId = Math.max(0L, sourceMaxTaskId);
        }
    }

    public record Projection(List<Map<String, Object>> messages,
                             long compactionEpoch,
                             long sourceMaxSequence) {
        public Projection {
            messages = List.copyOf(messages == null ? List.of() : messages);
        }
    }
}
