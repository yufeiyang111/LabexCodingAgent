package com.labex.labexagent.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.labex.labexagent.runtime.AgentProviderMessageProjector;
import com.labex.mapper.AgentCompactionRecordMapper;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化 compaction epoch，并从最新完成记录重建 Provider 投影。 */
@Service
public class AgentCompactionService {
    private static final Gson GSON = new Gson();
    private static final Type MESSAGE_LIST = new TypeToken<List<Map<String, Object>>>() { }.getType();

    private final AgentCompactionRecordMapper mapper;
    private final AgentProviderMessageProjector projector = new AgentProviderMessageProjector();

    public AgentCompactionService(AgentCompactionRecordMapper mapper) {
        this.mapper = mapper;
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
        record.setEstimatedTokensBefore(Math.max(0, request.estimatedTokensBefore()));
        record.setModelWindowTokens(Math.max(0, request.modelWindowTokens()));
        record.setReservedOutputTokens(Math.max(0, request.reservedOutputTokens()));
        mapper.insert(record);
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(AgentCompactionRecord record, String summary, int estimatedTokensAfter) {
        requirePersisted(record);
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Completed compaction summary is required");
        }
        record.setSummary(summary);
        record.setEstimatedTokensAfter(Math.max(0, estimatedTokensAfter));
        record.setFailureReason(null);
        record.setStatus("completed");
        mapper.updateById(record);
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(AgentCompactionRecord record, String failureReason) {
        requirePersisted(record);
        record.setFailureReason(safe(failureReason));
        record.setStatus("failed");
        mapper.updateById(record);
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
        result.put("executionEpoch", record.getExecutionEpoch());
        result.put("compactionEpoch", record.getCompactionEpoch());
        result.put("triggerReason", record.getTriggerReason());
        result.put("status", record.getStatus());
        result.put("retainedTurns", record.getRetainedTurns());
        result.put("sourceMaxSequence", record.getSourceMaxSequence());
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
                .eq(AgentCompactionRecord::getTaskId, taskId);
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
        if (record == null || record.getCompactionId() == null) {
            throw new IllegalArgumentException("Persisted compaction record is required");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
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

    public record Projection(List<Map<String, Object>> messages,
                             long compactionEpoch,
                             long sourceMaxSequence) {
        public Projection {
            messages = List.copyOf(messages == null ? List.of() : messages);
        }
    }
}
