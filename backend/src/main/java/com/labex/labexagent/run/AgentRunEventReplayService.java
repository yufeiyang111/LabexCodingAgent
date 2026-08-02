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
