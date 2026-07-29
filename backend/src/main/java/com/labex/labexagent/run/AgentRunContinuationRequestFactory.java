package com.labex.labexagent.run;

import com.google.gson.Gson;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
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
        request.setResumeTaskId(task.getTaskId());
        request.setActivePath(text(payload.get("activePath")));
        request.setModelConfigId(integer(payload.get("modelConfigId")));
        String original = text(payload.get("message"));
        String resumeNote = continuation == null ? "" : continuation.strip();
        if (original.isBlank()) {
            request.setMessage(resumeNote.isBlank() ? "Continue the existing task from durable state."
                    : "Continue the existing task from durable state.\n" + resumeNote);
        } else if (resumeNote.isBlank()) {
            request.setMessage(original);
        } else {
            request.setMessage("Original user objective (continue this exact task):\n" + original
                    + "\n\nDurable continuation context:\n" + resumeNote);
        }
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
