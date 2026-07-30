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
        if (existing == null) {
            existing = interactionMapper.selectOne(new QueryWrapper<AgentRunInteraction>()
                    .eq("task_id", request.taskId())
                    .eq("idempotency_key", request.idempotencyKey())
                    .last("LIMIT 1"));
        }
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

    /** 查询当前任务是否已经针对同一网络请求获得一次性批准。 */
    public boolean hasApprovedNetworkGrant(Long taskId, String requestDigest) {
        return hasApprovedNetworkGrant(taskId, requestDigest, null);
    }

    /** 按请求类型查询批准，确保离线失败重试不会误用普通网络授权。 */
    public boolean hasApprovedNetworkGrant(Long taskId, String requestDigest, String requestKind) {
        if (taskId == null || requestDigest == null || requestDigest.isBlank()) return false;
        List<AgentRunInteraction> interactions = interactionMapper.selectList(new QueryWrapper<AgentRunInteraction>()
                .eq("task_id", taskId)
                .eq("interaction_type", "network")
                .eq("status", "approved")
                .orderByDesc("update_time")
                .last("LIMIT 20"));
        LocalDateTime now = LocalDateTime.now();
        for (AgentRunInteraction interaction : interactions) {
            if (interaction == null || interaction.getRequestPayload() == null
                    || (interaction.getExpiresTime() != null && interaction.getExpiresTime().isBefore(now))) {
                continue;
            }
            try {
                Map<?, ?> payload = GSON.fromJson(interaction.getRequestPayload(), Map.class);
                Object digest = payload == null ? null : payload.get("requestDigest");
                Object kind = payload == null ? null : payload.get("requestKind");
                boolean kindMatches = requestKind == null || requestKind.equals(String.valueOf(kind));
                if (requestDigest.equals(String.valueOf(digest)) && kindMatches) return true;
            } catch (RuntimeException ignored) {
                // 忽略损坏的历史审批记录，避免阻塞当前任务。
            }
        }
        return false;
    }

    /** 查询当前任务是否已经记录过指定类型的网络交互，避免离线失败反复弹出审批。 */
    public boolean hasNetworkInteraction(Long taskId, String requestDigest, String requestKind) {
        if (taskId == null || requestDigest == null || requestDigest.isBlank()
                || requestKind == null || requestKind.isBlank()) return false;
        List<AgentRunInteraction> interactions = interactionMapper.selectList(new QueryWrapper<AgentRunInteraction>()
                .eq("task_id", taskId)
                .eq("interaction_type", "network")
                .orderByDesc("update_time")
                .last("LIMIT 50"));
        for (AgentRunInteraction interaction : interactions) {
            if (interaction == null || interaction.getRequestPayload() == null) continue;
            try {
                Map<?, ?> payload = GSON.fromJson(interaction.getRequestPayload(), Map.class);
                Object digest = payload == null ? null : payload.get("requestDigest");
                Object kind = payload == null ? null : payload.get("requestKind");
                if (requestDigest.equals(String.valueOf(digest)) && requestKind.equals(String.valueOf(kind))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 忽略损坏的历史审批记录，避免阻塞当前任务。
            }
        }
        return false;
    }

    public boolean consumeApprovedNetworkGrant(Integer studentId, Integer projectId, Long taskId, String requestDigest) {
        if (studentId == null || projectId == null || taskId == null || requestDigest == null || requestDigest.isBlank()) {
            return false;
        }
        List<AgentRunInteraction> interactions = interactionMapper.selectList(new QueryWrapper<AgentRunInteraction>()
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("task_id", taskId)
                .eq("interaction_type", "network")
                .eq("status", "approved")
                .orderByDesc("update_time")
                .last("LIMIT 20"));
        LocalDateTime now = LocalDateTime.now();
        for (AgentRunInteraction interaction : interactions) {
            if (interaction == null || (interaction.getExpiresTime() != null && interaction.getExpiresTime().isBefore(now))) continue;
            try {
                Map<?, ?> payload = GSON.fromJson(interaction.getRequestPayload(), Map.class);
                Object digest = payload == null ? null : payload.get("requestDigest");
                if (!requestDigest.equals(String.valueOf(digest))) continue;
                int updated = interactionMapper.update(null, new UpdateWrapper<AgentRunInteraction>()
                        .eq("interaction_id", interaction.getInteractionId())
                        .eq("status", "approved")
                        .set("status", "consumed")
                        .set("response_payload", GSON.toJson(Map.of("grantStatus", "consumed", "requestDigest", requestDigest)))
                        .set("update_time", now));
                if (updated == 1) return true;
            } catch (RuntimeException ignored) {
                // 忽略损坏的历史审批记录，避免阻塞当前任务。
            }
        }
        return false;
    }

    public AgentRunInteraction findWaitingForTask(Long taskId) {
        if (taskId == null) return null;
        return interactionMapper.selectOne(new QueryWrapper<AgentRunInteraction>()
                .eq("task_id", taskId)
                .eq("status", "waiting")
                .orderByDesc("create_time")
                .last("LIMIT 1"));
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRunInteraction respond(Integer studentId, Integer projectId, String interactionId,
                                       String status, Object responsePayload) {
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(interactionId, "interactionId");
        require(status, "status");

        AgentRunInteraction interaction = interactionMapper.selectById(interactionId);
        requireOwnedInteraction(interaction, studentId, projectId);
        validateResolutionStatus(interaction, status);
        if (!"waiting".equals(interaction.getStatus())) {
            return requireCompatibleReplay(interaction, status);
        }

        LocalDateTime now = LocalDateTime.now();
        if (isExpired(interaction, now)) {
            expireWaitingInteraction(interaction, now);
            throw new IllegalArgumentException("Agent interaction has expired");
        }

        String responseJson = GSON.toJson(responsePayload);
        int updated = interactionMapper.update(null, new UpdateWrapper<AgentRunInteraction>()
                .eq("interaction_id", interactionId)
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("status", "waiting")
                .and(wrapper -> wrapper.isNull("expires_time").or().gt("expires_time", now))
                .set("status", status)
                .set("response_payload", responseJson)
                .set("update_time", now));
        if (updated != 1) {
            AgentRunInteraction current = interactionMapper.selectById(interactionId);
            requireOwnedInteraction(current, studentId, projectId);
            if (!"waiting".equals(current.getStatus())) {
                return requireCompatibleReplay(current, status);
            }
            if (isExpired(current, now)) {
                expireWaitingInteraction(current, now);
                throw new IllegalArgumentException("Agent interaction has expired");
            }
            throw new IllegalStateException("Agent interaction changed concurrently; retry the same decision");
        }
        interaction.setStatus(status);
        interaction.setResponsePayload(responseJson);
        interaction.setUpdateTime(now);
        return interaction;
    }

    private void requireOwnedInteraction(AgentRunInteraction interaction, Integer studentId, Integer projectId) {
        if (interaction == null
                || !Objects.equals(interaction.getStudentId(), studentId)
                || !Objects.equals(interaction.getProjectId(), projectId)) {
            throw new IllegalArgumentException("Agent interaction not found");
        }
    }

    private void validateResolutionStatus(AgentRunInteraction interaction, String status) {
        boolean valid = switch (String.valueOf(interaction.getInteractionType())) {
            case "question" -> "answered".equals(status) || "cancelled".equals(status);
            case "permission", "network" -> "approved".equals(status) || "rejected".equals(status);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid resolution status for interaction type");
        }
    }

    private AgentRunInteraction requireCompatibleReplay(AgentRunInteraction interaction, String requestedStatus) {
        if (Objects.equals(interaction.getStatus(), requestedStatus)) {
            return interaction;
        }
        throw new IllegalArgumentException(
                "Agent interaction was already resolved as " + interaction.getStatus());
    }

    private boolean isExpired(AgentRunInteraction interaction, LocalDateTime now) {
        return interaction.getExpiresTime() != null && !interaction.getExpiresTime().isAfter(now);
    }

    private void expireWaitingInteraction(AgentRunInteraction interaction, LocalDateTime now) {
        Map<String, Object> payload = Map.of(
                "reason", "Interaction timed out",
                "expiredAt", now.toString());
        String responseJson = GSON.toJson(payload);
        int updated = interactionMapper.update(null, new UpdateWrapper<AgentRunInteraction>()
                .eq("interaction_id", interaction.getInteractionId())
                .eq("status", "waiting")
                .isNotNull("expires_time")
                .le("expires_time", now)
                .set("status", "timed_out")
                .set("response_payload", responseJson)
                .set("update_time", now));
        if (updated == 1) {
            interaction.setStatus("timed_out");
            interaction.setResponsePayload(responseJson);
            interaction.setUpdateTime(now);
        }
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
