package com.labex.labexagent.network;

import com.google.gson.Gson;
import com.labex.entity.AgentRunInteraction;
import com.labex.labexagent.run.AgentRunInteractionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 管理一次性网络访问审批，审批结果只允许绑定到当前任务的当前命令。
 */
@Service
public class NetworkAccessService {
    private static final Gson GSON = new Gson();
    private static final String INTERACTION_TYPE = "network";
    private static final String SCOPE = "single_command";

    private final AgentRunInteractionService interactionService;

    public NetworkAccessService(AgentRunInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    public NetworkAccessRequest begin(Integer studentId, Integer projectId, Long taskId,
                                      String conversationId, String sessionId, String toolName,
                                      String request, String summary, List<String> domains) {
        return begin(studentId, projectId, taskId, conversationId, sessionId, toolName, request,
                summary, "explicit_command", false, null, domains);
    }

    public NetworkAccessRequest begin(Integer studentId, Integer projectId, Long taskId,
                                      String conversationId, String sessionId, String toolName,
                                      String request, String summary, String requestKind,
                                      boolean retryable, String attemptKey, List<String> domains) {
        return begin(studentId, projectId, taskId, conversationId, sessionId, toolName, request, summary,
                requestKind, retryable, attemptKey, attemptKey, domains);
    }

    public NetworkAccessRequest begin(Integer studentId, Integer projectId, Long taskId,
                                      String conversationId, String sessionId, String toolName,
                                      String request, String summary, String requestKind,
                                      boolean retryable, String attemptKey, String toolCallId,
                                      List<String> domains) {
        return beginInternal(studentId, projectId, taskId, conversationId, sessionId, toolName,
                request, summary, requestKind, retryable, attemptKey, toolCallId, domains, Map.of());
    }

    /** 为已失败的持久化命令创建一次性网络重试交互，并绑定原 approvalId。 */
    public NetworkAccessRequest beginOfflineCommandRetry(Integer studentId, Integer projectId, Long taskId,
                                                         String conversationId, String sessionId, String toolName,
                                                         String request, String summary, String attemptKey,
                                                         String toolCallId, String approvalId,
                                                         List<String> domains) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (approvalId != null && !approvalId.isBlank()) metadata.put("approvalId", approvalId);
        return beginInternal(studentId, projectId, taskId, conversationId, sessionId, toolName,
                request, summary, "offline_failure_retry", true, attemptKey, toolCallId, domains, metadata);
    }

    private NetworkAccessRequest beginInternal(Integer studentId, Integer projectId, Long taskId,
                                               String conversationId, String sessionId, String toolName,
                                               String request, String summary, String requestKind,
                                               boolean retryable, String attemptKey, String toolCallId,
                                               List<String> domains, Map<String, Object> metadata) {
        require(studentId, "studentId");
        require(projectId, "projectId");
        require(taskId, "taskId");
        require(toolName, "toolName");
        String normalizedRequest = request == null ? "" : request.trim();
        String requestDigest = digest(normalizedRequest);
        String interactionId = "network-" + UUID.randomUUID();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(1);
        String normalizedKind = requestKind == null || requestKind.isBlank()
                ? "explicit_command" : requestKind.trim();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interactionType", INTERACTION_TYPE);
        payload.put("toolName", toolName);
        payload.put("request", normalizedRequest);
        payload.put("requestDigest", requestDigest);
        payload.put("requestKind", normalizedKind);
        payload.put("retryable", retryable);
        if (toolCallId != null && !toolCallId.isBlank()) {
            payload.put("toolCallId", toolCallId);
        }
        payload.put("summary", summary == null ? "Agent \u8bf7\u6c42\u4f7f\u7528\u7f51\u7edc\u5b8c\u6210\u5f53\u524d\u547d\u4ee4" : summary);
        payload.put("domains", domains == null ? List.of() : List.copyOf(domains));
        payload.put("scope", SCOPE);
        payload.put("networkMode", "isolated_bridge");
        payload.put("expiresTime", expiresAt.toString());
        if (metadata != null && !metadata.isEmpty()) payload.putAll(metadata);
        AgentRunInteraction persisted = interactionService.createWaiting(
                new AgentRunInteractionService.WaitingInteraction(
                        interactionId, taskId, conversationId, sessionId, studentId, projectId,
                        INTERACTION_TYPE, payload,
                        idempotencyKey(taskId, requestDigest, normalizedKind, attemptKey), expiresAt));
        String persistedId = persisted == null || persisted.getInteractionId() == null
                || persisted.getInteractionId().isBlank() ? interactionId : persisted.getInteractionId();
        Map<String, Object> authoritativePayload = persistedPayload(persisted, payload);
        String authoritativeDigest = stringValue(authoritativePayload.get("requestDigest"));
        String authoritativeRequest = stringValue(authoritativePayload.get("request"));
        return new NetworkAccessRequest(
                persistedId,
                authoritativeDigest.isBlank() ? requestDigest : authoritativeDigest,
                authoritativeRequest.isBlank() ? normalizedRequest : authoritativeRequest,
                authoritativePayload);
    }

    private Map<String, Object> persistedPayload(AgentRunInteraction persisted, Map<String, Object> fallback) {
        if (persisted == null || persisted.getRequestPayload() == null || persisted.getRequestPayload().isBlank()) {
            return Map.copyOf(fallback);
        }
        try {
            Map<?, ?> raw = GSON.fromJson(persisted.getRequestPayload(), Map.class);
            if (raw == null || raw.isEmpty()) {
                return Map.copyOf(fallback);
            }
            Map<String, Object> restored = new LinkedHashMap<>();
            raw.forEach((key, value) -> restored.put(String.valueOf(key), value));
            return Map.copyOf(restored);
        } catch (RuntimeException ignored) {
            return Map.copyOf(fallback);
        }
    }

    public boolean hasApprovedGrant(Long taskId, String request) {
        return taskId != null && interactionService.hasApprovedNetworkGrant(
                taskId, digest(request == null ? "" : request.trim()));
    }

    /** 仅识别由已批准命令的离线失败产生的精确一次重试授权。 */
    public boolean hasApprovedOfflineRetryGrant(Long taskId, String request) {
        return taskId != null && interactionService.hasApprovedNetworkGrant(
                taskId, digest(request == null ? "" : request.trim()), "offline_failure_retry");
    }

    public boolean hasOfflineRetryAttempt(Long taskId, String request) {
        return taskId != null && interactionService.hasNetworkInteraction(
                taskId, digest(request == null ? "" : request.trim()), "offline_failure_retry");
    }

    public boolean consumeGrant(Integer studentId, Integer projectId, Long taskId, String request) {
        return interactionService.consumeApprovedNetworkGrant(
                studentId, projectId, taskId, digest(request == null ? "" : request.trim()));
    }

    /** 网络审批不依赖技术栈白名单；目标域名仅在未来有可靠解析器时作为展示信息。 */
    public OfflineRetryDescriptor offlineRetryDescriptor(AgentRunInteraction interaction) {
        if (interaction == null || !INTERACTION_TYPE.equals(interaction.getInteractionType())
                || interaction.getRequestPayload() == null || interaction.getRequestPayload().isBlank()) {
            return null;
        }
        try {
            Map<?, ?> payload = GSON.fromJson(interaction.getRequestPayload(), Map.class);
            if (payload == null || !"offline_failure_retry".equals(String.valueOf(payload.get("requestKind")))) {
                return null;
            }
            String request = stringValue(payload.get("request"));
            String requestDigest = stringValue(payload.get("requestDigest"));
            String toolCallId = stringValue(payload.get("toolCallId"));
            if (request.isBlank() || requestDigest.isBlank() || toolCallId.isBlank()
                    || !requestDigest.equals(digest(request))) {
                return null;
            }
            return new OfflineRetryDescriptor(
                    stringValue(payload.get("approvalId")), toolCallId,
                    stringValue(payload.get("toolName")), request, requestDigest);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public AgentRunInteractionService.NetworkRetryClaim claimOfflineRetry(AgentRunInteraction interaction) {
        if (interaction == null) throw new IllegalArgumentException("network interaction is required");
        return interactionService.claimApprovedNetworkRetry(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getInteractionId());
    }

    public AgentRunInteraction completeOfflineRetry(AgentRunInteraction interaction, Object responsePayload) {
        if (interaction == null) throw new IllegalArgumentException("network interaction is required");
        return interactionService.completeClaimedNetworkRetry(
                interaction.getStudentId(), interaction.getProjectId(), interaction.getInteractionId(), responsePayload);
    }

    public List<AgentRunInteraction> claimedOfflineRetries(int limit) {
        return interactionService.findClaimedNetworkRetries(limit).stream()
                .filter(candidate -> offlineRetryDescriptor(candidate) != null)
                .toList();
    }

    private String stringValue(Object value) {
        return value == null || "null".equals(String.valueOf(value)) ? "" : String.valueOf(value);
    }

    public List<String> domainsFor(String toolName, String request) {
        return List.of();
    }

    public String digest(String request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((request == null ? "" : request).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash network request", exception);
        }
    }

    private String idempotencyKey(Long taskId, String requestDigest, String requestKind, String attemptKey) {
        String attemptDigest = digest((requestKind == null ? "" : requestKind) + ":"
                + (attemptKey == null ? "" : attemptKey));
        return "network-access:v2:" + taskId + ":" + requestDigest + ":" + attemptDigest.substring(0, 16);
    }

    private void require(Object value, String name) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record OfflineRetryDescriptor(String approvalId, String toolCallId, String toolName,
                                         String command, String requestDigest) {
    }

    public record NetworkAccessRequest(String requestId, String requestDigest, String request,
                                       Map<String, Object> payload) {
    }
}
