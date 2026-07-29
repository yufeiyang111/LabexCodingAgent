package com.labex.labexagent.run;

import com.labex.entity.AgentTask;
import java.util.LinkedHashMap;
import java.util.Map;

/** 将现有持久化 Conversation 映射为 OpenCode 风格 Session，并保留执行 session 标识。 */
public record AgentRunSessionSnapshot(
        String sessionId,
        String conversationId,
        String executionSessionId,
        Long taskId,
        String state,
        Long runVersion,
        Long lastEventSequence) {

    public static AgentRunSessionSnapshot from(AgentTask task) {
        if (task == null) return null;
        String conversationId = task.getConversationId() == null ? "" : task.getConversationId();
        return new AgentRunSessionSnapshot(
                conversationId,
                conversationId,
                task.getSessionId() == null ? "" : task.getSessionId(),
                task.getTaskId(),
                task.getStatus() == null ? "unknown" : task.getStatus(),
                task.getRunVersion() == null ? 0L : task.getRunVersion(),
                task.getLastEventSequence() == null ? 0L : task.getLastEventSequence());
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", sessionId);
        payload.put("conversationId", conversationId);
        payload.put("executionSessionId", executionSessionId);
        payload.put("taskId", taskId);
        payload.put("state", state);
        payload.put("runVersion", runVersion);
        payload.put("lastEventSequence", lastEventSequence);
        return payload;
    }
}