package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将 {@code t_agent_message} 的旧行一次性迁移到 durable task/event/message/part graph。
 *
 * <p>迁移完成后写入版本标记；后续 conversation history、UI、Provider 和
 * statistics 都不得再从旧表读取。</p>
 */
@Service
public class AgentLegacyConversationHistoryMigrationService {
    public static final String DURABLE_VERSION = "durable-v1";
    private static final Gson GSON = new Gson();

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper legacyMessageMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunEventMapper eventMapper;
    private final AgentRunMessageMapper runMessageMapper;
    private final AgentRunPartMapper runPartMapper;
    private final AgentRunLifecycleService lifecycleService;

    public AgentLegacyConversationHistoryMigrationService(AgentConversationMapper conversationMapper,
                                                          AgentMessageMapper legacyMessageMapper,
                                                          AgentTaskMapper taskMapper,
                                                          AgentRunEventMapper eventMapper,
                                                          AgentRunMessageMapper runMessageMapper,
                                                          AgentRunPartMapper runPartMapper,
                                                          AgentRunLifecycleService lifecycleService) {
        this.conversationMapper = conversationMapper;
        this.legacyMessageMapper = legacyMessageMapper;
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.runMessageMapper = runMessageMapper;
        this.runPartMapper = runPartMapper;
        this.lifecycleService = lifecycleService;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean ensureMigrated(Integer studentId, Integer projectId, String conversationId) {
        AgentConversation conversation = ownedConversationForUpdate(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        if (DURABLE_VERSION.equals(conversation.getHistoryProjectionVersion())) {
            return false;
        }

        List<AgentMessage> legacy = legacyMessageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getStudentId, studentId)
                .eq(AgentMessage::getProjectId, projectId)
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByAsc(AgentMessage::getMessageId));
        List<LegacyTurn> turns = splitTurns(legacy, conversation);
        List<AgentTask> existing = taskMapper.selectList(new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getStudentId, studentId)
                .eq(AgentTask::getProjectId, projectId)
                .eq(AgentTask::getConversationId, conversationId)
                .ne(AgentTask::getMode, "compact")
                .orderByAsc(AgentTask::getTaskId));
        List<AgentTask> tasks = existing == null ? List.of() : existing.stream()
                .filter(task -> ownedTask(task, studentId, projectId, conversationId))
                .toList();

        for (int index = 0; index < turns.size(); index++) {
            LegacyTurn turn = turns.get(index);
            AgentTask task = index < tasks.size() ? tasks.get(index)
                    : createImportedTask(studentId, projectId, conversation, turn);
            ensureRequestPayload(task, turn);
            if (hasDurableProjection(task.getTaskId())) {
                continue;
            }
            for (AgentMessage legacyEvent : turn.events()) {
                String eventType = normalizeEventType(legacyEvent.getEventType());
                if (eventType.isBlank() || "USER".equals(eventType)) continue;
                lifecycleService.appendEvent(task.getTaskId(), eventType,
                        migratedPayload(legacyEvent, eventType),
                        "legacy-message:" + legacyEvent.getMessageId());
            }
        }

        conversation.setHistoryProjectionVersion(DURABLE_VERSION);
        conversation.setHistoryMigratedAt(LocalDateTime.now());
        conversation.setUpdateTime(LocalDateTime.now());
        if (conversationMapper.updateById(conversation) != 1) {
            throw new IllegalStateException("Unable to mark durable conversation history migration");
        }
        return legacy != null && !legacy.isEmpty();
    }

    private AgentConversation ownedConversationForUpdate(Integer studentId, Integer projectId, String conversationId) {
        if (studentId == null || projectId == null || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getStudentId, studentId)
                .eq(AgentConversation::getProjectId, projectId)
                .eq(AgentConversation::getConversationId, conversationId)
                .eq(AgentConversation::getStatus, 1)
                .last("FOR UPDATE"));
    }

    private List<LegacyTurn> splitTurns(List<AgentMessage> rows, AgentConversation conversation) {
        if (rows == null || rows.isEmpty()) return List.of();
        List<LegacyTurn> result = new ArrayList<>();
        LegacyTurnBuilder current = null;
        for (AgentMessage row : rows) {
            if (row == null || row.getMessageId() == null) continue;
            if ("USER".equals(normalizeEventType(row.getEventType()))) {
                if (current != null) result.add(current.build());
                current = new LegacyTurnBuilder(row, visibleContent(row));
                continue;
            }
            if (current == null) {
                String fallback = conversation.getTitle() == null ? "Imported legacy conversation" : conversation.getTitle();
                current = new LegacyTurnBuilder(row, fallback);
            }
            current.events.add(row);
        }
        if (current != null) result.add(current.build());
        return List.copyOf(result);
    }

    private AgentTask createImportedTask(Integer studentId, Integer projectId,
                                         AgentConversation conversation, LegacyTurn turn) {
        LocalDateTime createdAt = firstTime(turn);
        LocalDateTime finishedAt = lastTime(turn, createdAt);
        AgentTask task = new AgentTask();
        task.setConversationId(conversation.getConversationId());
        task.setSessionId("legacy-" + turn.userMessageId());
        task.setStudentId(studentId);
        task.setProjectId(projectId);
        task.setTitle(limit(turn.userContent(), 500));
        task.setMode("legacy_import");
        task.setStatus(inferTerminalStatus(turn.events()));
        task.setCurrentStep("Imported legacy conversation history");
        task.setSummary("One-time migration from t_agent_message");
        task.setRunVersion(0L);
        task.setLastEventSequence(0L);
        task.setRequestPayload(requestPayload(turn));
        task.setRecoveryAttempts(0);
        task.setRetryAttempts(0);
        task.setExecutionEpoch(0L);
        task.setSubmittedAt(createdAt);
        task.setStartedAt(createdAt);
        task.setFinishedAt(finishedAt);
        task.setElapsedMs(Math.max(0L, java.time.Duration.between(createdAt, finishedAt).toMillis()));
        task.setActiveElapsedMs(0L);
        task.setCreateTime(createdAt);
        task.setUpdateTime(finishedAt);
        if (taskMapper.insert(task) != 1 || task.getTaskId() == null) {
            throw new IllegalStateException("Unable to create imported durable Agent task");
        }
        return task;
    }

    private void ensureRequestPayload(AgentTask task, LegacyTurn turn) {
        if (task == null) return;
        String current = task.getRequestPayload();
        if (current != null && !current.isBlank()) return;
        task.setRequestPayload(requestPayload(turn));
        if (taskMapper.updateById(task) != 1) {
            throw new IllegalStateException("Unable to attach imported request payload to Agent task");
        }
    }

    private String requestPayload(LegacyTurn turn) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", turn.userContent());
        payload.put("legacyHistoryMigrated", true);
        payload.put("legacyUserMessageId", turn.userMessageId());
        return GSON.toJson(payload);
    }

    private boolean hasDurableProjection(Long taskId) {
        if (taskId == null) return false;
        Long events = eventMapper.selectCount(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId));
        if (events != null && events > 0) return true;
        Long messages = runMessageMapper.selectCount(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId));
        if (messages != null && messages > 0) return true;
        Long parts = runPartMapper.selectCount(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId));
        return parts != null && parts > 0;
    }

    private Object migratedPayload(AgentMessage message, String eventType) {
        Object decoded = Map.of("content", visibleContent(message));
        String eventData = message.getEventData();
        if (eventData != null && !eventData.isBlank()) {
            try {
                decoded = GSON.fromJson(eventData, Object.class);
            } catch (RuntimeException ignored) {
                // 损坏 JSON 退回可见 content，不能让单条旧事件阻断整个会话迁移。
            }
        }
        return InternalReasoningBoundary.sanitizeEventPayload(eventType, decoded);
    }

    private String visibleContent(AgentMessage message) {
        return InternalReasoningBoundary.stripVisible(message == null ? "" : message.getContent());
    }

    private String inferTerminalStatus(List<AgentMessage> events) {
        String status = "completed";
        for (AgentMessage event : events == null ? List.<AgentMessage>of() : events) {
            String type = normalizeEventType(event.getEventType());
            if (type.contains("CANCEL") || "INTERRUPTED".equals(type)) return "cancelled";
            if ("ERROR".equals(type) || type.endsWith("FAILED")) status = "failed";
        }
        return status;
    }

    private String normalizeEventType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean ownedTask(AgentTask task, Integer studentId, Integer projectId, String conversationId) {
        return task != null && task.getTaskId() != null
                && java.util.Objects.equals(studentId, task.getStudentId())
                && java.util.Objects.equals(projectId, task.getProjectId())
                && java.util.Objects.equals(conversationId, task.getConversationId());
    }

    private LocalDateTime firstTime(LegacyTurn turn) {
        LocalDateTime value = turn.userEvent() == null ? null : turn.userEvent().getCreateTime();
        return value == null ? LocalDateTime.now() : value;
    }

    private LocalDateTime lastTime(LegacyTurn turn, LocalDateTime fallback) {
        LocalDateTime value = fallback;
        for (AgentMessage event : turn.events()) {
            if (event.getCreateTime() != null && event.getCreateTime().isAfter(value)) value = event.getCreateTime();
        }
        return value;
    }

    private String limit(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private record LegacyTurn(AgentMessage userEvent, long userMessageId,
                              String userContent, List<AgentMessage> events) {
        private LegacyTurn {
            userContent = userContent == null ? "" : userContent;
            events = List.copyOf(events == null ? List.of() : events);
        }
    }

    private static final class LegacyTurnBuilder {
        private final AgentMessage userEvent;
        private final long userMessageId;
        private final String userContent;
        private final List<AgentMessage> events = new ArrayList<>();

        private LegacyTurnBuilder(AgentMessage userEvent, String userContent) {
            this.userEvent = userEvent;
            this.userMessageId = userEvent == null || userEvent.getMessageId() == null
                    ? 0L : userEvent.getMessageId();
            this.userContent = userContent;
        }

        private LegacyTurn build() {
            return new LegacyTurn(userEvent, userMessageId, userContent, events);
        }
    }
}
