package com.labex.labexagent.runtime;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * \u4e0a\u4e0b\u6587\u7528\u91cf\u662f\u53ef\u91cd\u5efa\u7684\u6d3e\u751f\u89c6\u56fe\uff1b\u5185\u5b58\u7f13\u5b58\u53ea\u7528\u4e8e\u70ed\u8def\u5f84\uff0c\u91cd\u542f\u540e\u5fc5\u987b\u4ece durable CONTEXT_STATUS \u4e8b\u4ef6\u6062\u590d\u3002
 */
@Service
public class ContextUsageRegistry {
    private static final Gson GSON = new Gson();

    private final Map<String, ContextUsageSnapshot> snapshotsByConversation = new ConcurrentHashMap<>();
    private final AgentRunEventMapper eventMapper;
    private final AgentTaskMapper taskMapper;

    public ContextUsageRegistry(AgentRunEventMapper eventMapper, AgentTaskMapper taskMapper) {
        this.eventMapper = eventMapper;
        this.taskMapper = taskMapper;
    }

    public void save(ContextUsageSnapshot snapshot) {
        if (snapshot == null) return;
        Object conversationId = snapshot.toPayload().get("conversationId");
        if (conversationId instanceof String id && !id.isBlank()) snapshotsByConversation.put(id, snapshot);
    }

    public Optional<ContextUsageSnapshot> find(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        ContextUsageSnapshot cached = snapshotsByConversation.get(conversationId);
        if (cached != null) return Optional.of(cached);
        ContextUsageSnapshot restored = loadDurable(conversationId);
        if (restored != null) {
            snapshotsByConversation.put(conversationId, restored);
            return Optional.of(restored);
        }
        return Optional.empty();
    }

    private ContextUsageSnapshot loadDurable(String conversationId) {
        List<AgentTask> tasks = taskMapper.selectList(new QueryWrapper<AgentTask>()
                .eq("conversation_id", conversationId)
                .select("task_id"));
        if (tasks == null || tasks.isEmpty()) return null;
        List<Long> taskIds = tasks.stream().map(AgentTask::getTaskId).filter(id -> id != null).toList();
        if (taskIds.isEmpty()) return null;
        List<AgentRunEvent> events = eventMapper.selectList(new QueryWrapper<AgentRunEvent>()
                .in("task_id", taskIds)
                .eq("event_type", "CONTEXT_STATUS")
                .orderByDesc("event_id")
                .last("LIMIT 1"));
        if (events == null || events.isEmpty()) return null;
        AgentRunEvent event = events.get(0);
        try {
            Map<String, Object> payload = GSON.fromJson(event.getPayload(), Map.class);
            return ContextUsageSnapshot.fromPayload(payload);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
