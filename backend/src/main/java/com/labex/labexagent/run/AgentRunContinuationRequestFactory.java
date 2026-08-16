package com.labex.labexagent.run;

import com.google.gson.Gson;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import java.util.Map;

/** Rebuilds a resume request from the durable task objective instead of replacing it. */
public final class AgentRunContinuationRequestFactory {
    private static final Gson GSON = new Gson();

    private AgentRunContinuationRequestFactory() {
    }

    public static AgentStreamRequest fromTask(AgentTask task, String continuation) {
        if (task == null) throw new IllegalArgumentException("task is required");
        Map<?, ?> payload = parse(task.getRequestPayload());
        AgentStreamRequest request = new AgentStreamRequest();
        request.setSessionId(task.getSessionId());
        request.setConversationId(task.getConversationId());
        request.setMode(nonBlank(text(payload.get("mode")), task.getMode()));
        // 恢复只能读取 task 的不可变 profile snapshot，绝不信任旧 payload 或调用方当前选择。
        request.setRuntimeProfile(AgentRuntimeProfile.fromPersisted(task.getRuntimeProfile()).persistedValue());
        request.setResumeTaskId(task.getTaskId());
        request.setActivePath(text(payload.get("activePath")));
        // 优先使用 durable 的 t_agent_task.model_config_id 列；payload 回退仅用于兼容旧任务与展示目的。
        // 恢复路径的模型解析只读取 task.getModelConfigId()：两者都为 null 时解析层 fail closed，
        // 绝不会静默回退到用户当前默认模型。
        request.setModelConfigId(task.getModelConfigId() != null
                ? task.getModelConfigId()
                : integer(payload.get("modelConfigId")));
        String original = text(payload.get("message"));
        String displayMessage = text(payload.get("displayMessage"));
        String resumeNote = continuation == null ? "" : continuation.strip();
        request.setMessage(original.isBlank()
                ? "Continue the existing task from durable state."
                : original);
        request.setDisplayMessage(displayMessage.isBlank() ? original : displayMessage);
        request.setResumeNote(resumeNote);
        return request;
    }

    private static Map<?, ?> parse(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            Map<?, ?> value = GSON.fromJson(payload, Map.class);
            return value == null ? Map.of() : value;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null || String.valueOf(value).isBlank() ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
