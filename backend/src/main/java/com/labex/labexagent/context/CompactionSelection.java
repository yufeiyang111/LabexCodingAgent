package com.labex.labexagent.context;

import com.labex.labexagent.runtime.AgentProviderMessageProjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一次压缩的完整 head/tail 边界；tail 永远从真实 user turn 边界开始。 */
public record CompactionSelection(List<Map<String, Object>> compactedHead,
                                  List<Map<String, Object>> retainedTail,
                                  int tailStartIndex,
                                  int retainedTurns,
                                  int estimatedHeadTokens,
                                  int estimatedTailTokens) {

    public CompactionSelection {
        compactedHead = immutableCopy(compactedHead);
        retainedTail = immutableCopy(retainedTail);
        tailStartIndex = Math.max(0, tailStartIndex);
        retainedTurns = Math.max(0, retainedTurns);
        estimatedHeadTokens = Math.max(0, estimatedHeadTokens);
        estimatedTailTokens = Math.max(0, estimatedTailTokens);
    }

    public static CompactionSelection select(List<Map<String, Object>> messages,
                                             int maxTailTurns,
                                             int tailTokenBudget,
                                             AgentRequestTokenEstimator estimator) {
        List<Map<String, Object>> source = messages == null ? List.of() : messages;
        AgentRequestTokenEstimator safeEstimator = estimator == null
                ? new AgentRequestTokenEstimator() : estimator;
        if (source.isEmpty()) {
            return unchanged(source, safeEstimator);
        }
        List<Integer> userStarts = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if ("user".equalsIgnoreCase(String.valueOf(source.get(index).get("role")))) {
                userStarts.add(index);
            }
        }
        if (userStarts.size() <= 1) {
            return unchanged(source, safeEstimator);
        }

        int turnLimit = Math.max(1, maxTailTurns);
        int budget = Math.max(1, tailTokenBudget);
        int latestTurnPosition = userStarts.size() - 1;
        int earliestCandidatePosition = Math.max(0, userStarts.size() - turnLimit);
        int tailStart = userStarts.get(latestTurnPosition);
        int retained = 1;
        int tailTokens = safeEstimator.estimateMessages(source.subList(tailStart, source.size()));

        for (int position = latestTurnPosition - 1; position >= earliestCandidatePosition; position--) {
            int candidateStart = userStarts.get(position);
            int candidateTokens = safeEstimator.estimateMessages(source.subList(candidateStart, tailStart));
            if (tailTokens + candidateTokens > budget) {
                break;
            }
            tailStart = candidateStart;
            tailTokens += candidateTokens;
            retained++;
        }
        if (tailStart <= 0) {
            return unchanged(source, safeEstimator);
        }
        List<Map<String, Object>> head = source.subList(0, tailStart);
        List<Map<String, Object>> tail = source.subList(tailStart, source.size());
        return new CompactionSelection(head, tail, tailStart, retained,
                safeEstimator.estimateMessages(head), safeEstimator.estimateMessages(tail));
    }

    public boolean changed() {
        return !compactedHead.isEmpty() && !retainedTail.isEmpty();
    }

    public List<Map<String, Object>> projectedWithSummary(String summary) {
        if (!changed() || summary == null || summary.isBlank()) {
            return retainedTail;
        }
        List<Map<String, Object>> projected = new ArrayList<>(retainedTail.size() + 1);
        projected.add(Map.of("role", "user", "content", summary));
        projected.addAll(retainedTail);
        return new AgentProviderMessageProjector().project(projected);
    }

    private static CompactionSelection unchanged(List<Map<String, Object>> source,
                                                 AgentRequestTokenEstimator estimator) {
        return new CompactionSelection(List.of(), source, 0, countUserTurns(source),
                0, estimator.estimateMessages(source));
    }

    private static int countUserTurns(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> message : messages) {
            if ("user".equalsIgnoreCase(String.valueOf(message.get("role")))) {
                count++;
            }
        }
        return count;
    }

    private static List<Map<String, Object>> immutableCopy(List<Map<String, Object>> messages) {
        return new AgentProviderMessageProjector().project(messages == null ? List.of() : messages);
    }
}