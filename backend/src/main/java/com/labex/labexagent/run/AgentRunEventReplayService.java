package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class AgentRunEventReplayService {
    private static final long MAX_REPLAY_EVENTS = 1000L;
    private static final Gson GSON = new Gson();

    private static final java.util.Set<String> CONFIG_PROPOSAL_EVENT_TYPES = java.util.Set.of(
            "CONFIG_PROPOSAL_CREATED", "CONFIG_PROPOSAL_DECIDED",
            "CONFIG_REVISION_APPLIED", "CONFIG_PROPOSAL_FAILED");

    private final AgentTaskMapper taskMapper;
    private final AgentRunEventMapper eventMapper;

    public AgentRunEventReplayService(AgentTaskMapper taskMapper, AgentRunEventMapper eventMapper) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
    }

    public List<AgentRunEvent> eventsAfter(Integer studentId, Integer projectId, Long taskId, long sequenceNumber) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null
                || !Objects.equals(task.getStudentId(), studentId)
                || !Objects.equals(task.getProjectId(), projectId)) {
            throw new IllegalArgumentException("Agent run not found");
        }
        long after = Math.max(0L, sequenceNumber);
        List<AgentRunEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId)
                .gt(AgentRunEvent::getSequenceNumber, after)
                .orderByAsc(AgentRunEvent::getSequenceNumber)
                .last("LIMIT " + MAX_REPLAY_EVENTS));
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        events.forEach(this::normalizeLegacyReasoningPayload);
        return events;
    }

    /**
     * Task 2.3 的 durable 刷新/回放投影：只返回属于该任务（且归属该校学生/项目）的
     * config proposal 事件。事件 payload 只含 ID/digest/状态，前端无需 SSE 即可重建
     * 阻塞中的 proposal 等待。
     */
    public List<AgentRunEvent> configProposalEvents(Integer studentId, Integer projectId, Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null
                || !Objects.equals(task.getStudentId(), studentId)
                || !Objects.equals(task.getProjectId(), projectId)) {
            throw new IllegalArgumentException("Agent run not found");
        }
        List<AgentRunEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AgentRunEvent>()
                .eq(AgentRunEvent::getTaskId, taskId)
                .in(AgentRunEvent::getEventType, CONFIG_PROPOSAL_EVENT_TYPES)
                .orderByAsc(AgentRunEvent::getSequenceNumber)
                .last("LIMIT " + MAX_REPLAY_EVENTS));
        return events == null || events.isEmpty() ? List.of() : List.copyOf(events);
    }

    private void normalizeLegacyReasoningPayload(AgentRunEvent event) {
        if (event == null
                || !InternalReasoningBoundary.requiresEventPayloadSanitization(event.getEventType())
                || event.getPayload() == null
                || event.getPayload().isBlank()) {
            return;
        }
        try {
            Object decoded = GSON.fromJson(event.getPayload(), Object.class);
            Object safe = InternalReasoningBoundary.sanitizeEventPayload(event.getEventType(), decoded);
            event.setPayload(GSON.toJson(safe));
        } catch (RuntimeException ignored) {
            // 损坏的历史事件由现有 SSE 安全错误投影处理，不能阻断后续游标回放。
        }
    }
}
