package com.labex.labexagent.service;

import com.labex.entity.AgentRunInteraction;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunResumeScheduler;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AgentInteractionService {
    private static final long QUESTION_TIMEOUT_SECONDS = 600L;

    private final AgentRunInteractionService runInteractionService;
    private final AgentRunResumeScheduler resumeScheduler;

    public AgentInteractionService() {
        this(null, null);
    }

    public AgentInteractionService(AgentRunInteractionService runInteractionService) {
        this(runInteractionService, null);
    }

    @Autowired
    public AgentInteractionService(AgentRunInteractionService runInteractionService,
                                   @Lazy AgentRunResumeScheduler resumeScheduler) {
        this.runInteractionService = runInteractionService;
        this.resumeScheduler = resumeScheduler;
    }

    public UserQuestionRequest beginQuestion(
            Integer projectId,
            Integer studentId,
            String sessionId,
            Long taskId,
            String conversationId,
            String question,
            String summary,
            List<String> options) {
        String requestId = UUID.randomUUID().toString();
        UserQuestionRequest request = new UserQuestionRequest(
                requestId,
                projectId,
                studentId,
                sessionId,
                taskId,
                conversationId,
                question,
                summary,
                options == null ? List.of() : List.copyOf(options),
                System.currentTimeMillis());
        this.persistQuestion(request);
        return request;
    }

    public UserQuestionResult reply(Integer projectId, Integer studentId, String requestId, String action, String answer) {
        UserQuestionResult result = handleReply(action, answer);
        if (runInteractionService == null) {
            return UserQuestionResult.cancelled("Durable question storage is unavailable");
        }
        final AgentRunInteraction persisted;
        try {
            persisted = runInteractionService.respond(
                    studentId,
                    projectId,
                    requestId,
                    interactionStatus(result),
                    responsePayload(result, action));
        } catch (IllegalArgumentException e) {
            return UserQuestionResult.cancelled(e.getMessage());
        }
        if (resumeScheduler != null) {
            resumeScheduler.resumeIfWaiting(persisted);
        }
        return result;
    }

    private void persistQuestion(UserQuestionRequest request) {
        if (runInteractionService == null) {
            throw new IllegalStateException("Durable question storage is unavailable");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", request.question());
        payload.put("summary", request.summary());
        payload.put("options", request.options());
        runInteractionService.createWaiting(new AgentRunInteractionService.WaitingInteraction(
                request.requestId(),
                request.taskId(),
                request.conversationId(),
                request.sessionId(),
                request.studentId(),
                request.projectId(),
                "question",
                payload,
                "question-" + request.requestId(),
                LocalDateTime.now().plusSeconds(QUESTION_TIMEOUT_SECONDS)));
    }

    private String interactionStatus(UserQuestionResult result) {
        if (result.answered()) {
            return "answered";
        }
        if (result.timedOut()) {
            return "timed_out";
        }
        return "cancelled";
    }

    private Map<String, Object> responsePayload(UserQuestionResult result, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action == null ? "" : action);
        payload.put("answer", result.answer());
        payload.put("feedback", result.feedback());
        return payload;
    }

    private UserQuestionResult handleReply(String action, String answer) {
        String normalized = action == null ? "" : action.trim().toLowerCase();
        return switch (normalized) {
            case "answer", "reply", "submit" -> {
                String safeAnswer = answer == null ? "" : answer.trim();
                if (safeAnswer.isBlank()) {
                    yield UserQuestionResult.cancelled("Answer is required");
                }
                yield UserQuestionResult.answered(safeAnswer);
            }
            case "skip" -> UserQuestionResult.cancelled("User skipped this question");
            case "cancel", "reject" -> UserQuestionResult.cancelled("User cancelled this question");
            default -> UserQuestionResult.cancelled("Unknown question response");
        };
    }

    public record UserQuestionRequest(
            String requestId,
            Integer projectId,
            Integer studentId,
            String sessionId,
            Long taskId,
            String conversationId,
            String question,
            String summary,
            List<String> options,
            long createdAt) {
    }

    public record UserQuestionResult(
            boolean answered,
            boolean cancelled,
            boolean timedOut,
            String answer,
            String feedback) {

        public static UserQuestionResult answered(String answer) {
            return new UserQuestionResult(true, false, false, answer, "");
        }

        public static UserQuestionResult cancelled(String feedback) {
            return new UserQuestionResult(false, true, false, "", feedback == null ? "" : feedback);
        }

        public static UserQuestionResult timedOut(String feedback) {
            return new UserQuestionResult(false, false, true, "", feedback == null ? "" : feedback);
        }
    }
}
