package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentRunInteraction;
import com.labex.mapper.AgentRunInteractionMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunInteractionService {
    private static final Gson GSON = new Gson();

    private final AgentRunInteractionMapper interactionMapper;

    public AgentRunInteractionService(AgentRunInteractionMapper interactionMapper) {
        this.interactionMapper = interactionMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunInteraction createWaiting(WaitingInteraction request) {
        require(request, "request");
        require(request.interactionId(), "interactionId");
        require(request.taskId(), "taskId");
        require(request.studentId(), "studentId");
        require(request.projectId(), "projectId");
        require(request.interactionType(), "interactionType");
        require(request.idempotencyKey(), "idempotencyKey");

        AgentRunInteraction existing = interactionMapper.selectById(request.interactionId());
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId(request.interactionId());
        interaction.setTaskId(request.taskId());
        interaction.setConversationId(request.conversationId());
        interaction.setSessionId(request.sessionId());
        interaction.setStudentId(request.studentId());
        interaction.setProjectId(request.projectId());
        interaction.setInteractionType(request.interactionType());
        interaction.setStatus("waiting");
        interaction.setRequestPayload(GSON.toJson(request.requestPayload()));
        interaction.setIdempotencyKey(request.idempotencyKey());
        interaction.setExpiresTime(request.expiresTime());
        interaction.setCreateTime(now);
        interaction.setUpdateTime(now);
        if (interactionMapper.insert(interaction) != 1) {
            throw new IllegalStateException("Unable to persist agent run interaction");
        }
        return interaction;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunInteraction respond(Integer studentId, Integer projectId, String interactionId,
                                       String status, Object responsePayload) {
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(interactionId, "interactionId");
        require(status, "status");

        AgentRunInteraction interaction = interactionMapper.selectById(interactionId);
        if (interaction == null
                || !Objects.equals(interaction.getStudentId(), studentId)
                || !Objects.equals(interaction.getProjectId(), projectId)) {
            throw new IllegalArgumentException("Agent interaction not found");
        }
        if (!"waiting".equals(interaction.getStatus())) {
            return interaction;
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = interactionMapper.update(null, new UpdateWrapper<AgentRunInteraction>()
                .eq("interaction_id", interactionId)
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("status", "waiting")
                .set("status", status)
                .set("response_payload", GSON.toJson(responsePayload))
                .set("update_time", now));
        if (updated != 1) {
            AgentRunInteraction current = interactionMapper.selectById(interactionId);
            if (current == null) {
                throw new IllegalArgumentException("Agent interaction not found");
            }
            return current;
        }
        interaction.setStatus(status);
        interaction.setResponsePayload(GSON.toJson(responsePayload));
        interaction.setUpdateTime(now);
        return interaction;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<AgentRunInteraction> claimExpired(LocalDateTime now, int limit) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        int effectiveLimit = Math.max(1, Math.min(limit, 500));
        List<AgentRunInteraction> candidates = interactionMapper.selectList(new QueryWrapper<AgentRunInteraction>()
                .eq("status", "waiting")
                .isNotNull("expires_time")
                .le("expires_time", effectiveNow)
                .orderByAsc("expires_time")
                .last("LIMIT " + effectiveLimit));
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgentRunInteraction> claimed = new ArrayList<>();
        for (AgentRunInteraction interaction : candidates) {
            Map<String, Object> payload = Map.of(
                    "reason", "Interaction timed out",
                    "expiredAt", effectiveNow.toString());
            int updated = interactionMapper.update(null, new UpdateWrapper<AgentRunInteraction>()
                    .eq("interaction_id", interaction.getInteractionId())
                    .eq("status", "waiting")
                    .le("expires_time", effectiveNow)
                    .set("status", "timed_out")
                    .set("response_payload", GSON.toJson(payload))
                    .set("update_time", effectiveNow));
            if (updated == 1) {
                interaction.setStatus("timed_out");
                interaction.setResponsePayload(GSON.toJson(payload));
                interaction.setUpdateTime(effectiveNow);
                claimed.add(interaction);
            }
        }
        return List.copyOf(claimed);
    }

    private void require(Object value, String name) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record WaitingInteraction(
            String interactionId,
            Long taskId,
            String conversationId,
            String sessionId,
            Integer studentId,
            Integer projectId,
            String interactionType,
            Object requestPayload,
            String idempotencyKey,
            LocalDateTime expiresTime) {
    }
}
