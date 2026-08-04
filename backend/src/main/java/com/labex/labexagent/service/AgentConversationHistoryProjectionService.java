package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.labexagent.run.AgentRunMessageService;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 会话历史的 durable projector。
 *
 * <p>权威输入只来自 Task/RunEvent/RunMessage/RunPart；按照不可变的 fork task
 * boundary 拼接谱系。DTO 复用 SSE 的 event type/payload，前端因此可以使用同一 reducer 重放。</p>
 */
@Service
public class AgentConversationHistoryProjectionService {
    public static final String PROJECTION_VERSION = "durable-task-history-v1";
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_LINEAGE_DEPTH = 32;
    private static final int MAX_PAGE_EVENTS = 20_000;
    private static final int DEFAULT_MEMORY_LIMIT_TOKENS = 12_000;
    private static final Gson GSON = new Gson();

    private final AgentConversationMapper conversationMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentRunMessageService runMessageService;
    private final AgentRunPartService runPartService;
    private final AgentConversationForkBoundaryService forkBoundaries;
    private final AgentLegacyConversationHistoryMigrationService migrationService;
    private final AgentConversationMemoryProjectionService memoryProjection;
    private final AgentRequestTokenEstimator tokenEstimator = new AgentRequestTokenEstimator();

    public AgentConversationHistoryProjectionService(AgentConversationMapper conversationMapper,
                                                     AgentTaskMapper taskMapper,
                                                     AgentRunEventMapper eventMapper,
                                                     AgentRunMessageService runMessageService,
                                                     AgentRunPartService runPartService,
                                                     AgentConversationForkBoundaryService forkBoundaries,
                                                     AgentLegacyConversationHistoryMigrationService migrationService,
                                                     AgentConversationMemoryProjectionService memoryProjection) {
        this.conversationMapper = conversationMapper;
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.runMessageService = runMessageService;
        this.runPartService = runPartService;
        this.forkBoundaries = forkBoundaries;
        this.migrationService = migrationService;
        this.memoryProjection = memoryProjection;
    }

    public HistoryPage page(Integer studentId, Integer projectId, String conversationId,
                            Long beforeTaskIdExclusive, int limit) {
        int safeLimit = Math.min(MAX_PAGE_SIZE, Math.max(1, limit));
        MigrationFlag migration = new MigrationFlag();
        List<LineageSegment> segments = lineage(studentId, projectId, conversationId, migration);
        List<TaskSource> candidates = new ArrayList<>();
        for (LineageSegment segment : segments) {
            List<AgentTask> selected = taskMapper.selectList(new LambdaQueryWrapper<AgentTask>()
                    .eq(AgentTask::getStudentId, studentId)
                    .eq(AgentTask::getProjectId, projectId)
                    .eq(AgentTask::getConversationId, segment.conversationId())
                    .lt(beforeTaskIdExclusive != null && beforeTaskIdExclusive > 0,
                            AgentTask::getTaskId, beforeTaskIdExclusive)
                    .le(segment.maxTaskIdInclusive() != null && segment.maxTaskIdInclusive() > 0,
                            AgentTask::getTaskId, segment.maxTaskIdInclusive())
                    .orderByDesc(AgentTask::getTaskId)
                    .last("LIMIT " + (safeLimit + 1)));
            for (AgentTask task : selected == null ? List.<AgentTask>of() : selected) {
                if (!ownedTask(task, studentId, projectId, segment)) continue;
                if (beforeTaskIdExclusive != null && beforeTaskIdExclusive > 0
                        && task.getTaskId() >= beforeTaskIdExclusive) continue;
                if (segment.maxTaskIdInclusive() != null
                        && task.getTaskId() > segment.maxTaskIdInclusive()) continue;
                candidates.add(new TaskSource(task, segment.conversationId(),
                        !Objects.equals(segment.conversationId(), conversationId)));
            }
        }

        Map<Long, TaskSource> unique = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparing((TaskSource source) -> source.task().getTaskId()).reversed())
                .forEach(source -> unique.putIfAbsent(source.task().getTaskId(), source));
        List<TaskSource> newest = new ArrayList<>(unique.values());
        boolean hasMore = newest.size() > safeLimit;
        if (hasMore) newest = new ArrayList<>(newest.subList(0, safeLimit));
        newest.sort(Comparator.comparing(source -> source.task().getTaskId()));

        List<Long> taskIds = newest.stream().map(source -> source.task().getTaskId()).toList();
        Map<Long, List<HistoryEvent>> eventsByTask = loadEvents(studentId, projectId, taskIds);
        Map<Long, List<Map<String, Object>>> messagesByTask = taskIds.isEmpty()
                ? Map.of() : runMessageService.publicHistoryByTaskIds(taskIds);
        Map<Long, List<Map<String, Object>>> partsByTask = taskIds.isEmpty()
                ? Map.of() : runPartService.publicHistoryByTaskIds(taskIds);
        List<HistoryTurn> turns = newest.stream()
                .map(source -> historyTurn(conversationId, source,
                        eventsByTask.getOrDefault(source.task().getTaskId(), List.of()),
                        messagesByTask.getOrDefault(source.task().getTaskId(), List.of()),
                        partsByTask.getOrDefault(source.task().getTaskId(), List.of())))
                .toList();
        Long cursor = hasMore && !turns.isEmpty() ? turns.get(0).taskId() : null;
        return new HistoryPage(PROJECTION_VERSION, conversationId, turns, hasMore, cursor, migration.migrated);
    }

    public boolean ensureMigrated(Integer studentId, Integer projectId, String conversationId) {
        MigrationFlag migration = new MigrationFlag();
        lineage(studentId, projectId, conversationId, migration);
        return migration.migrated;
    }

    public MemoryStats memoryStats(Integer studentId, Integer projectId, String conversationId) {
        ensureMigrated(studentId, projectId, conversationId);
        if (memoryProjection == null) return new MemoryStats(0, 0, false, DEFAULT_MEMORY_LIMIT_TOKENS);
        AgentConversationMemoryProjectionService.Projection projection =
                memoryProjection.project(studentId, projectId, conversationId, null);
        List<Map<String, Object>> messages = projection == null ? List.of() : projection.messages();
        int estimated = tokenEstimator.estimateMessages(messages);
        boolean needsCompact = estimated >= (DEFAULT_MEMORY_LIMIT_TOKENS * 4 / 5);
        return new MemoryStats(estimated, messages.size(), needsCompact, DEFAULT_MEMORY_LIMIT_TOKENS);
    }

    private List<LineageSegment> lineage(Integer studentId, Integer projectId, String conversationId,
                                         MigrationFlag migration) {
        List<LineageSegment> result = new ArrayList<>();
        collectLineage(studentId, projectId, conversationId, null,
                new HashSet<>(), 0, result, migration);
        return List.copyOf(result);
    }

    private void collectLineage(Integer studentId, Integer projectId, String conversationId,
                                Long maxTaskIdInclusive, Set<String> visited, int depth,
                                List<LineageSegment> result, MigrationFlag migration) {
        if (depth >= MAX_LINEAGE_DEPTH) {
            throw new IllegalStateException("Conversation fork lineage exceeds the maximum depth");
        }
        if (!visited.add(conversationId)) {
            throw new IllegalStateException("Conversation fork cycle detected at " + conversationId);
        }
        try {
            if (migrationService != null) {
                migration.migrated |= migrationService.ensureMigrated(studentId, projectId, conversationId);
            }
            AgentConversation conversation = ownedConversation(studentId, projectId, conversationId);
            if (conversation == null) throw new IllegalArgumentException("Conversation not found");
            String parent = conversation.getParentConversationId();
            if (parent != null && !parent.isBlank()) {
                Long boundary = conversation.getForkedFromTaskId();
                if ((boundary == null || boundary <= 0) && forkBoundaries != null) {
                    boundary = forkBoundaries.resolveExistingFork(conversation);
                }
                if (boundary != null && boundary > 0) {
                    Long parentBoundary = maxTaskIdInclusive == null
                            ? boundary : Math.min(boundary, maxTaskIdInclusive);
                    collectLineage(studentId, projectId, parent, parentBoundary,
                            visited, depth + 1, result, migration);
                }
            }
            result.add(new LineageSegment(conversationId, maxTaskIdInclusive));
        } finally {
            visited.remove(conversationId);
        }
    }

    private AgentConversation ownedConversation(Integer studentId, Integer projectId, String conversationId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getStudentId, studentId)
                .eq(AgentConversation::getProjectId, projectId)
                .eq(AgentConversation::getConversationId, conversationId)
                .eq(AgentConversation::getStatus, 1));
    }

    private boolean ownedTask(AgentTask task, Integer studentId, Integer projectId, LineageSegment segment) {
        return task != null && task.getTaskId() != null
                && Objects.equals(studentId, task.getStudentId())
                && Objects.equals(projectId, task.getProjectId())
                && Objects.equals(segment.conversationId(), task.getConversationId());
    }

    private Map<Long, List<HistoryEvent>> loadEvents(Integer studentId, Integer projectId, List<Long> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        List<AgentRunEvent> stored = eventMapper.selectList(new LambdaQueryWrapper<AgentRunEvent>()
                .in(AgentRunEvent::getTaskId, taskIds)
                .eq(AgentRunEvent::getStudentId, studentId)
                .eq(AgentRunEvent::getProjectId, projectId)
                .orderByAsc(AgentRunEvent::getTaskId)
                .orderByAsc(AgentRunEvent::getSequenceNumber));
        if (stored != null && stored.size() > MAX_PAGE_EVENTS) {
            throw new IllegalStateException("Conversation history page exceeds the durable event safety limit");
        }
        Set<Long> allowed = Set.copyOf(taskIds);
        Map<Long, List<HistoryEvent>> grouped = new LinkedHashMap<>();
        for (AgentRunEvent event : stored == null ? List.<AgentRunEvent>of() : stored) {
            if (event == null || event.getTaskId() == null || !allowed.contains(event.getTaskId())) continue;
            grouped.computeIfAbsent(event.getTaskId(), ignored -> new ArrayList<>())
                    .add(publicEvent(event));
        }
        grouped.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparing(HistoryEvent::sequence))
                .toList());
        return Map.copyOf(grouped);
    }

    private HistoryEvent publicEvent(AgentRunEvent event) {
        Object data = Map.of();
        if (event.getPayload() != null && !event.getPayload().isBlank()) {
            try {
                data = GSON.fromJson(event.getPayload(), Object.class);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Stored Agent event payload is invalid for task "
                        + event.getTaskId() + " sequence " + event.getSequenceNumber(), failure);
            }
        }
        Object safe = InternalReasoningBoundary.sanitizeEventPayload(event.getEventType(), data);
        return new HistoryEvent(event.getEventId(), event.getTaskId(),
                event.getSequenceNumber() == null ? 0L : event.getSequenceNumber(),
                event.getState(), event.getEventType(), safe, event.getCreateTime());
    }

    private HistoryTurn historyTurn(String requestedConversationId, TaskSource source,
                                    List<HistoryEvent> events,
                                    List<Map<String, Object>> runMessages,
                                    List<Map<String, Object>> parts) {
        AgentTask task = source.task();
        return new HistoryTurn(task.getTaskId(), requestedConversationId, source.sourceConversationId(),
                source.inherited(), task.getSessionId(), task.getMode(), task.getStatus(),
                task.getCurrentStep(), task.getSummary(), durableRequest(task),
                task.getLastEventSequence() == null ? 0L : task.getLastEventSequence(),
                task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch(),
                task.getSubmittedAt(), task.getStartedAt(), task.getFinishedAt(),
                task.getActiveElapsedMs() == null ? 0L : task.getActiveElapsedMs(),
                task.getCreateTime(), task.getUpdateTime(), events, runMessages, parts);
    }

    private String durableRequest(AgentTask task) {
        String payload = task.getRequestPayload();
        if (payload != null && !payload.isBlank()) {
            try {
                Map<?, ?> parsed = GSON.fromJson(payload, Map.class);
                Object displayMessage = parsed == null ? null : parsed.get("displayMessage");
                if (displayMessage != null && !String.valueOf(displayMessage).isBlank()) {
                    return InternalReasoningBoundary.stripVisible(String.valueOf(displayMessage));
                }
                Object message = parsed == null ? null : parsed.get("message");
                if (message != null && !String.valueOf(message).isBlank()) {
                    return InternalReasoningBoundary.stripVisible(String.valueOf(message));
                }
            } catch (RuntimeException ignored) {
                // 损坏的旧 payload 只能退回 title，不能再次读取 AgentMessage。
            }
        }
        return InternalReasoningBoundary.stripVisible(task.getTitle());
    }

    private record LineageSegment(String conversationId, Long maxTaskIdInclusive) {}
    private record TaskSource(AgentTask task, String sourceConversationId, boolean inherited) {}
    private static final class MigrationFlag { private boolean migrated; }

    public record HistoryPage(String projectionVersion, String conversationId,
                              List<HistoryTurn> turns, boolean hasMore,
                              Long nextBeforeTaskId, boolean legacyMigrated) {
        public HistoryPage {
            turns = List.copyOf(turns == null ? List.of() : turns);
        }
    }

    public record HistoryTurn(Long taskId, String conversationId, String sourceConversationId,
                              boolean inherited, String sessionId, String mode, String status,
                              String currentStep, String summary, String userContent,
                              long lastEventSequence, long executionEpoch,
                              LocalDateTime submittedAt, LocalDateTime startedAt,
                              LocalDateTime finishedAt, long activeElapsedMs,
                              LocalDateTime createdAt, LocalDateTime updatedAt,
                              List<HistoryEvent> events,
                              List<Map<String, Object>> runMessages,
                              List<Map<String, Object>> parts) {
        public HistoryTurn {
            events = List.copyOf(events == null ? List.of() : events);
            runMessages = List.copyOf(runMessages == null ? List.of() : runMessages);
            parts = List.copyOf(parts == null ? List.of() : parts);
        }
    }

    public record HistoryEvent(Long eventId, Long taskId, long sequence, String state,
                               String eventType, Object data, LocalDateTime createdAt) {}

    public record MemoryStats(int estimatedTokens, int messageCount,
                              boolean needsCompact, int maxTokens) {}
}
