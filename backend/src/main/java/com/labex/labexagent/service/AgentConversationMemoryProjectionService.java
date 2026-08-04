package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentConversation;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.runtime.AgentProviderMessageProjector;
import com.labex.mapper.AgentConversationMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 从 durable conversation graph 生成跨任务 Provider memory。
 *
 * <p>该服务只读取 Conversation fork task boundary、AgentTask/RunMessage transcript 与
 * completed conversation compaction；{@code t_agent_message} 不参与 Provider 投影。</p>
 */
@Service
public class AgentConversationMemoryProjectionService {
    private static final int MAX_LINEAGE_DEPTH = 32;
    private static final int MAX_TRANSCRIPT_BATCHES = 20;
    private static final int DEFAULT_CONTEXT_CHARS = 12_000;

    private final AgentConversationMapper conversationMapper;
    private final AgentConversationTranscriptProjectionService transcriptProjection;
    private final AgentCompactionService compactions;
    private final AgentConversationForkBoundaryService forkBoundaries;
    private final AgentProviderMessageProjector providerProjector = new AgentProviderMessageProjector();

    public AgentConversationMemoryProjectionService(AgentConversationMapper conversationMapper,
                                                    AgentConversationTranscriptProjectionService transcriptProjection,
                                                    AgentCompactionService compactions) {
        this(conversationMapper, transcriptProjection, compactions, null);
    }

    @Autowired
    public AgentConversationMemoryProjectionService(AgentConversationMapper conversationMapper,
                                                    AgentConversationTranscriptProjectionService transcriptProjection,
                                                    AgentCompactionService compactions,
                                                    AgentConversationForkBoundaryService forkBoundaries) {
        this.conversationMapper = conversationMapper;
        this.transcriptProjection = transcriptProjection;
        this.compactions = compactions;
        this.forkBoundaries = forkBoundaries;
    }

    public Projection project(Integer studentId, Integer projectId, String conversationId,
                              Long beforeTaskIdExclusive) {
        if (studentId == null || projectId == null || conversationId == null || conversationId.isBlank()) {
            return new Projection(List.of(), 0L, 0);
        }
        return projectRecursive(studentId, projectId, conversationId, normalizeBefore(beforeTaskIdExclusive),
                new HashSet<>(), 0);
    }

    public String buildContext(Integer studentId, Integer projectId, String conversationId) {
        return formatContext(project(studentId, projectId, conversationId, null), DEFAULT_CONTEXT_CHARS);
    }

    public String formatContext(Projection projection, int maxChars) {
        if (projection == null || projection.messages().isEmpty()) {
            return "";
        }
        int budget = Math.max(256, maxChars);
        List<String> entries = new ArrayList<>();
        for (Map<String, Object> message : projection.messages()) {
            if (message == null) {
                continue;
            }
            String content = String.valueOf(message.getOrDefault("content", "")).trim();
            if (content.isBlank()) {
                continue;
            }
            String role = String.valueOf(message.getOrDefault("role", "message"));
            String label = "assistant".equalsIgnoreCase(role) ? "ASSISTANT" : "USER";
            if (content.contains("<conversation-checkpoint")) {
                label = "CHECKPOINT";
            }
            entries.add("[" + label + "] " + content);
        }
        if (entries.isEmpty()) {
            return "";
        }
        String header = "持久化会话上下文（sourceTaskId=" + projection.sourceMaxTaskId() + "):\n";
        String joined = header + String.join("\n", entries);
        if (joined.length() <= budget) {
            return joined;
        }

        String checkpoint = entries.get(0).startsWith("[CHECKPOINT]") ? entries.get(0) : "";
        List<String> retained = new ArrayList<>();
        int used = header.length() + (checkpoint.isBlank() ? 0 : checkpoint.length() + 1);
        for (int index = entries.size() - 1; index >= 0; index--) {
            String entry = entries.get(index);
            if (entry.equals(checkpoint)) {
                continue;
            }
            if (used + entry.length() + 1 > budget) {
                continue;
            }
            retained.add(0, entry);
            used += entry.length() + 1;
        }
        StringBuilder result = new StringBuilder(header);
        if (!checkpoint.isBlank()) {
            result.append(limit(checkpoint, Math.max(128, budget / 2))).append('\n');
        }
        result.append(String.join("\n", retained));
        return limit(result.toString().trim(), budget);
    }

    private Projection projectRecursive(Integer studentId, Integer projectId, String conversationId,
                                        Long beforeTaskIdExclusive, Set<String> visited, int depth) {
        if (depth >= MAX_LINEAGE_DEPTH) {
            throw new IllegalStateException("Conversation fork lineage exceeds the maximum depth");
        }
        if (!visited.add(conversationId)) {
            throw new IllegalStateException("Conversation fork cycle detected at " + conversationId);
        }
        try {
            AgentConversation conversation = ownedConversation(studentId, projectId, conversationId);
            if (conversation == null) {
                throw new IllegalArgumentException("Conversation not found");
            }
            Long maxSourceTaskId = inclusiveBoundary(beforeTaskIdExclusive);
            Optional<AgentCompactionRecord> completed =
                    compactions.latestCompletedConversationAtOrBeforeTaskId(
                            studentId, projectId, conversationId, maxSourceTaskId);
            if (completed.isPresent()) {
                AgentCompactionRecord record = completed.get();
                long previousBoundary = positive(record.getSourceMaxTaskId());
                DirectProjection appended = projectDirect(
                        studentId, projectId, conversationId, previousBoundary, beforeTaskIdExclusive);
                AgentCompactionService.ConversationProjection restored =
                        compactions.projectConversation(record, appended.messages());
                List<Map<String, Object>> messages = providerProjector.project(restored.messages());
                return new Projection(messages, Math.max(previousBoundary, appended.sourceMaxTaskId()),
                        countUserTurns(messages));
            }

            Projection inherited = new Projection(List.of(), 0L, 0);
            if (conversation.getParentConversationId() != null
                    && !conversation.getParentConversationId().isBlank()) {
                Long forkTaskId = conversation.getForkedFromTaskId();
                if ((forkTaskId == null || forkTaskId <= 0) && forkBoundaries != null) {
                    forkTaskId = forkBoundaries.resolveExistingFork(conversation);
                }
                if (forkTaskId != null && forkTaskId > 0) {
                    Long parentBefore = plusOne(forkTaskId);
                    if (beforeTaskIdExclusive != null) {
                        parentBefore = Math.min(parentBefore, beforeTaskIdExclusive);
                    }
                    inherited = projectRecursive(studentId, projectId,
                            conversation.getParentConversationId(), parentBefore, visited, depth + 1);
                }
            }
            DirectProjection direct = projectDirect(studentId, projectId, conversationId,
                    inherited.sourceMaxTaskId(), beforeTaskIdExclusive);
            List<Map<String, Object>> combined = new ArrayList<>(inherited.messages());
            combined.addAll(direct.messages());
            List<Map<String, Object>> messages = providerProjector.project(combined);
            return new Projection(messages,
                    Math.max(inherited.sourceMaxTaskId(), direct.sourceMaxTaskId()),
                    countUserTurns(messages));
        } finally {
            visited.remove(conversationId);
        }
    }

    private DirectProjection projectDirect(Integer studentId, Integer projectId, String conversationId,
                                           long afterTaskIdExclusive, Long beforeTaskIdExclusive) {
        List<Map<String, Object>> messages = new ArrayList<>();
        long boundary = Math.max(0L, afterTaskIdExclusive);
        int userTurns = 0;
        for (int batch = 0; batch < MAX_TRANSCRIPT_BATCHES; batch++) {
            AgentConversationTranscriptProjectionService.Snapshot snapshot = transcriptProjection.snapshot(
                    studentId, projectId, conversationId, boundary, beforeTaskIdExclusive);
            messages.addAll(snapshot.messages());
            userTurns += snapshot.userTurns();
            long nextBoundary = Math.max(boundary, snapshot.sourceMaxTaskId());
            if (!snapshot.hasMore()) {
                return new DirectProjection(providerProjector.project(messages), nextBoundary, userTurns);
            }
            if (nextBoundary <= boundary) {
                throw new IllegalStateException("Conversation transcript pagination made no progress");
            }
            boundary = nextBoundary;
        }
        throw new IllegalStateException(
                "Conversation transcript exceeds the bounded projection scan; compact it before continuing");
    }

    private AgentConversation ownedConversation(Integer studentId, Integer projectId, String conversationId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getStudentId, studentId)
                .eq(AgentConversation::getProjectId, projectId)
                .eq(AgentConversation::getConversationId, conversationId)
                .eq(AgentConversation::getStatus, 1));
    }

    private int countUserTurns(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> message : messages == null ? List.<Map<String, Object>>of() : messages) {
            if (message != null && "user".equalsIgnoreCase(String.valueOf(message.get("role")))) {
                count++;
            }
        }
        return count;
    }

    private Long normalizeBefore(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private Long inclusiveBoundary(Long beforeTaskIdExclusive) {
        return beforeTaskIdExclusive == null ? null : Math.max(0L, beforeTaskIdExclusive - 1L);
    }

    private long positive(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private long plusOne(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private String limit(String value, int maxChars) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars);
    }

    private record DirectProjection(List<Map<String, Object>> messages,
                                    long sourceMaxTaskId,
                                    int userTurns) {
        private DirectProjection {
            messages = List.copyOf(messages == null ? List.of() : messages);
            sourceMaxTaskId = Math.max(0L, sourceMaxTaskId);
            userTurns = Math.max(0, userTurns);
        }
    }

    public record Projection(List<Map<String, Object>> messages,
                             long sourceMaxTaskId,
                             int userTurns) {
        public Projection {
            messages = List.copyOf(messages == null ? List.of() : messages);
            sourceMaxTaskId = Math.max(0L, sourceMaxTaskId);
            userTurns = Math.max(0, userTurns);
        }
    }
}