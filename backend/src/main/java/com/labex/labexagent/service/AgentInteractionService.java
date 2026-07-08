package com.labex.labexagent.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;

@Service
public class AgentInteractionService {
    private static final long QUESTION_TIMEOUT_SECONDS = 600L;
    private static final long POLL_MILLIS = 500L;

    private final ConcurrentHashMap<String, PendingQuestion> pendingQuestions = new ConcurrentHashMap<>();

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
        pendingQuestions.put(requestId, new PendingQuestion(request));
        return request;
    }

    public UserQuestionResult awaitQuestion(String requestId, BooleanSupplier cancelled)
            throws InterruptedException {
        PendingQuestion pending = pendingQuestions.get(requestId);
        if (pending == null) {
            return UserQuestionResult.cancelled("Question request not found");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUESTION_TIMEOUT_SECONDS);
        try {
            while (System.nanoTime() < deadline) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    UserQuestionResult result = UserQuestionResult.cancelled("Agent run was cancelled");
                    pending.future.complete(result);
                    return result;
                }
                try {
                    return pending.future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll again so cancellation can interrupt a waiting question.
                } catch (ExecutionException e) {
                    String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    return UserQuestionResult.cancelled(message);
                }
            }
            UserQuestionResult result = UserQuestionResult.timedOut("Question timed out after 10 minutes");
            pending.future.complete(result);
            return result;
        } finally {
            pendingQuestions.remove(requestId);
        }
    }

    public UserQuestionResult reply(Integer projectId, Integer studentId, String requestId, String action, String answer) {
        PendingQuestion pending = pendingQuestions.get(requestId);
        if (pending == null) {
            return UserQuestionResult.cancelled("Question request expired or not found");
        }
        UserQuestionRequest request = pending.request;
        if (!Objects.equals(request.projectId(), projectId) || !Objects.equals(request.studentId(), studentId)) {
            return UserQuestionResult.cancelled("Question request does not belong to this project or user");
        }
        UserQuestionResult result = handleReply(action, answer);
        pending.future.complete(result);
        return result;
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

    private static class PendingQuestion {
        private final UserQuestionRequest request;
        private final CompletableFuture<UserQuestionResult> future = new CompletableFuture<>();

        private PendingQuestion(UserQuestionRequest request) {
            this.request = request;
        }
    }
}
